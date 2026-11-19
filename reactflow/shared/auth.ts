import { createAuthConfig, AUTH_STORAGE_KEYS } from './config'

const AUTH = createAuthConfig()

export type AuthConfig = typeof AUTH

export function getAuthConfig(): AuthConfig {
  return AUTH
}

export function isOidcEnabled(): boolean {
  return AUTH.oidcEnabled
}

function nativeFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  return window.fetch(input, init)
}

function issuerBase(): string {
  return AUTH.issuer.replace(/\/+$/, '')
}

function randomString(length: number): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~'
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => chars[b % chars.length]).join('')
}

async function sha256(value: string): Promise<string> {
  const data = new TextEncoder().encode(value)
  const digest = await crypto.subtle.digest('SHA-256', data)
  const bytes = new Uint8Array(digest)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

export function oidcScope(): string {
  let scope = AUTH.scope
  if (AUTH.audience) {
    if (AUTH.apiScope) scope += ' ' + AUTH.apiScope
    if (!scope.split(/\s+/).includes('offline_access')) scope += ' offline_access'
  }
  return scope
}

function setFallbackOidcEndpoints(): void {
  const base = issuerBase()
  if (base.includes('/realms/')) {
    AUTH.authorizationEndpoint = base + '/protocol/openid-connect/auth'
    AUTH.tokenEndpoint = base + '/protocol/openid-connect/token'
    AUTH.logoutEndpoint = base + '/protocol/openid-connect/logout'
  } else {
    AUTH.authorizationEndpoint = base + '/authorize'
    AUTH.tokenEndpoint = base + '/oauth/token'
    AUTH.logoutEndpoint = issuerBase().includes('/realms/')
      ? issuerBase() + '/protocol/openid-connect/logout'
      : issuerBase() + '/oidc/logout'
  }
}

export async function ensureOidcConfig(): Promise<void> {
  if (!AUTH.oidcEnabled || !AUTH.issuer) return
  if (AUTH.authorizationEndpoint && AUTH.tokenEndpoint && AUTH.logoutEndpoint) return

  const discoveryUrl = issuerBase() + '/.well-known/openid-configuration'
  try {
    const res = await nativeFetch(discoveryUrl, { headers: { Accept: 'application/json' } })
    if (!res.ok) throw new Error('HTTP ' + res.status)
    const data = (await res.json()) as {
      authorization_endpoint?: string
      token_endpoint?: string
      end_session_endpoint?: string
    }
    if (!data.authorization_endpoint || !data.token_endpoint) {
      throw new Error('Discovery incomplete')
    }
    AUTH.authorizationEndpoint = data.authorization_endpoint
    AUTH.tokenEndpoint = data.token_endpoint
    AUTH.logoutEndpoint = data.end_session_endpoint || (issuerBase().includes('/realms/')
      ? issuerBase() + '/protocol/openid-connect/logout'
      : issuerBase() + '/oidc/logout')
    AUTH.discovery = data
  } catch (error) {
    console.warn('OIDC-Discovery fehlgeschlagen, nutze Fallback-Endpunkte:', error)
    setFallbackOidcEndpoints()
  }
}

export function tokenValid(): boolean {
  const token = AUTH.token || (typeof sessionStorage !== 'undefined' ? (sessionStorage.getItem('qoc.accessToken') || sessionStorage.getItem('qoc.idToken') || '') : '')
  return !!token && (!AUTH.expiresAt || Date.now() < AUTH.expiresAt - 30000)
}

function pkcePending(): boolean {
  try {
    return !!sessionStorage.getItem(AUTH_STORAGE_KEYS.pkceState)
  } catch {
    return false
  }
}

function clearPkce(): void {
  sessionStorage.removeItem(AUTH_STORAGE_KEYS.pkceVerifier)
  sessionStorage.removeItem(AUTH_STORAGE_KEYS.pkceState)
}

export function storeTokens(data: {
  access_token?: string
  refresh_token?: string
  expires_in?: number
  id_token?: string
}): void {
  AUTH.token = data.access_token || data.id_token || ''
  AUTH.refresh = data.refresh_token || AUTH.refresh || ''
  AUTH.expiresAt = Date.now() + (Number(data.expires_in || 300) * 1000)
  // Store the real access token for API calls; the ID token is kept separately.
  sessionStorage.setItem(AUTH_STORAGE_KEYS.accessToken, data.access_token || AUTH.token)
  sessionStorage.setItem(AUTH_STORAGE_KEYS.refreshToken, AUTH.refresh)
  sessionStorage.setItem(AUTH_STORAGE_KEYS.expiresAt, String(AUTH.expiresAt))
  if (data.id_token) sessionStorage.setItem(AUTH_STORAGE_KEYS.idToken, data.id_token)
  updateAuthStatus()
}

export function clearTokens(includePkce = false): void {
  AUTH.token = ''
  AUTH.refresh = ''
  AUTH.expiresAt = 0
  sessionStorage.removeItem(AUTH_STORAGE_KEYS.accessToken)
  sessionStorage.removeItem(AUTH_STORAGE_KEYS.refreshToken)
  sessionStorage.removeItem(AUTH_STORAGE_KEYS.expiresAt)
  sessionStorage.removeItem(AUTH_STORAGE_KEYS.idToken)
  if (includePkce) clearPkce()
  localStorage.removeItem('qoc.accessToken')
  localStorage.removeItem('qoc.refreshToken')
  updateAuthStatus()
}

export function authHeaders(headers?: HeadersInit): Headers {
  const result = new Headers(headers || {})
  const token = (typeof sessionStorage !== 'undefined' ? (sessionStorage.getItem('qoc.accessToken') || sessionStorage.getItem('qoc.idToken')) : '') || AUTH.token || ''
  if (token) result.set('Authorization', 'Bearer ' + token)
  return result
}

export async function refreshAccessToken(): Promise<unknown> {
  if (!AUTH.oidcEnabled) throw new Error('OIDC deaktiviert')
  if (!AUTH.refresh) throw new Error('Kein Refresh-Token')
  await ensureOidcConfig()
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: AUTH.clientId,
    refresh_token: AUTH.refresh,
  })
  const res = await nativeFetch(AUTH.tokenEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  const data = (await res.json().catch(() => ({}))) as {
    access_token?: string
    refresh_token?: string
    expires_in?: number
    id_token?: string
    error?: string
    error_description?: string
  }
  if (!res.ok) throw new Error(data.error_description || data.error || ('HTTP ' + res.status))
  storeTokens(data)
  return data
}

async function nativeFetchWithRetry(input: RequestInfo | URL, init?: RequestInit, retries = 2): Promise<Response> {
  let lastError: unknown
  for (let i = 0; i <= retries; i++) {
    try {
      return await nativeFetch(input, init)
    } catch (error) {
      lastError = error
    }
  }
  throw lastError
}

export async function apiFetch(input: RequestInfo | URL, init?: RequestInit, retries = 2): Promise<Response> {
  if (!AUTH.oidcEnabled) return nativeFetchWithRetry(input, init, retries)

  if (!pkcePending() && !tokenValid() && AUTH.refresh) {
    try {
      await refreshAccessToken()
    } catch {
      clearTokens()
    }
  }

  let opts: RequestInit = { ...(init || {}) }
  opts.headers = authHeaders(init?.headers)

  let res = await nativeFetchWithRetry(input, opts, retries)
  if (res.status === 401 && AUTH.refresh && !pkcePending()) {
    try {
      await refreshAccessToken()
      opts = { ...(init || {}) }
      opts.headers = authHeaders(init?.headers)
      res = await nativeFetchWithRetry(input, opts, retries)
    } catch {
      clearTokens()
      throw new Error('OIDC-Token konnte nicht erneuert werden')
    }
  }
  if (res.status === 401 && !pkcePending()) clearTokens()
  return res
}

export function updateAuthStatus(): void {
  const btn = document.getElementById('loginBtn')
  if (!AUTH.oidcEnabled) {
    if (btn) btn.style.display = 'none'
    return
  }
  if (btn) {
    btn.textContent = AUTH.token ? 'Logout' : 'Login'
    btn.style.display = ''
  }
}

export async function login(): Promise<void> {
  if (!AUTH.oidcEnabled) {
    alert('OIDC ist in diesem Modus deaktiviert.')
    return
  }
  if (!window.crypto?.subtle) {
    alert('Login requires HTTPS or http://localhost (Web Crypto / PKCE).')
    return
  }
  await ensureOidcConfig()
  const verifier = randomString(64)
  const challenge = await sha256(verifier)
  const state = randomString(32)
  sessionStorage.setItem(AUTH_STORAGE_KEYS.pkceVerifier, verifier)
  sessionStorage.setItem(AUTH_STORAGE_KEYS.pkceState, state)

  const params = new URLSearchParams({
    client_id: AUTH.clientId,
    redirect_uri: AUTH.redirectUri,
    response_type: 'code',
    scope: oidcScope(),
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  })
  if (AUTH.audience) params.set('audience', AUTH.audience)
  if (AUTH.authorizationEndpoint) window.location.assign(AUTH.authorizationEndpoint + '?' + params.toString())
}

export async function handleOidcCallback(): Promise<boolean> {
  if (!AUTH.oidcEnabled) return false
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  const state = params.get('state')
  const error = params.get('error')
  if (error) {
    const description = params.get('error_description') || error
    clearTokens(true)
    history.replaceState({}, document.title, window.location.pathname)
    alert('Login fehlgeschlagen: ' + description)
    return false
  }
  if (!code) return false
  console.log('OIDC callback: code found, exchanging...')
  await ensureOidcConfig()
  const expected = sessionStorage.getItem(AUTH_STORAGE_KEYS.pkceState)
  const verifier = sessionStorage.getItem(AUTH_STORAGE_KEYS.pkceVerifier)
  if (!expected || state !== expected || !verifier) {
    clearTokens(true)
    history.replaceState({}, document.title, window.location.pathname)
    alert('Login state invalid. Please sign in again.')
    return false
  }
  try {
    const body = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: AUTH.clientId,
      code,
      redirect_uri: AUTH.redirectUri,
      code_verifier: verifier,
    })
    const res = await nativeFetch(AUTH.tokenEndpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    })
    const data = (await res.json().catch(() => ({}))) as {
      access_token?: string
      refresh_token?: string
      expires_in?: number
      id_token?: string
      error?: string
      error_description?: string
    }
    if (!res.ok) throw new Error(data.error_description || data.error || ('HTTP ' + res.status))
    storeTokens(data)
    console.log('OIDC token stored, hasToken:', !!AUTH.token, 'tokenLen:', (AUTH.token||'').length)
    clearPkce()
    history.replaceState({}, document.title, window.location.pathname)
    return true
  } catch (error) {
    clearTokens(true)
    history.replaceState({}, document.title, window.location.pathname)
    alert('Token-Austausch fehlgeschlagen: ' + (error instanceof Error ? error.message : String(error)))
    return false
  }
}

export async function logout(): Promise<void> {
  if (!AUTH.oidcEnabled) {
    clearTokens(true)
    return
  }
  await ensureOidcConfig()
  const idToken = sessionStorage.getItem(AUTH_STORAGE_KEYS.idToken) || ''
  clearTokens(true)
  const params = new URLSearchParams({
    client_id: AUTH.clientId,
    post_logout_redirect_uri: AUTH.redirectUri,
  })
  if (idToken) params.set('id_token_hint', idToken)
  if (AUTH.logoutEndpoint) window.location.assign(AUTH.logoutEndpoint + '?' + params.toString())
}

export async function loginOrLogout(): Promise<void> {
  if (AUTH.token) await logout()
  else await login()
}

export function getToken(): string {
  return AUTH.token
}