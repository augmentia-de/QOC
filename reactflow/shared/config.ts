export interface QocAuthConfig {
  oidcEnabled: boolean
  issuer: string
  clientId: string
  audience: string
  apiScope: string
  redirectUri: string
  scope: string
  token: string
  refresh: string
  expiresAt: number
  authorizationEndpoint: string
  tokenEndpoint: string
  logoutEndpoint: string
  discovery?: unknown
}

const STORAGE_KEYS = {
  accessToken: 'qoc.accessToken',
  refreshToken: 'qoc.refreshToken',
  expiresAt: 'qoc.expiresAt',
  idToken: 'qoc.idToken',
  pkceVerifier: 'qoc.pkce.verifier',
  pkceState: 'qoc.pkce.state',
} as const

export const AUTH_STORAGE_KEYS = STORAGE_KEYS

export function createAuthConfig(): QocAuthConfig {
  const runtime = (typeof window !== 'undefined' ? (window as any).QOC_AUTH : {}) || {}
  const oidcEnabled = !!(
    runtime.oidcEnabled ||
    runtime.issuer ||
    (typeof localStorage !== 'undefined' && localStorage.getItem('qoc.oidcEnabled') === 'true') ||
    (typeof localStorage !== 'undefined' && localStorage.getItem('qoc.issuer'))
  )

  return {
    oidcEnabled,
    issuer: runtime.issuer || (typeof localStorage !== 'undefined' ? localStorage.getItem('qoc.issuer') : null) || '',
    clientId: runtime.clientId || (typeof localStorage !== 'undefined' ? localStorage.getItem('qoc.clientId') : null) || 'qoc-ui',
    audience: runtime.audience || (typeof localStorage !== 'undefined' ? localStorage.getItem('qoc.audience') : null) || '',
    apiScope: runtime.apiScope || (typeof localStorage !== 'undefined' ? localStorage.getItem('qoc.apiScope') : null) || '',
    redirectUri: typeof location !== 'undefined' ? location.origin + location.pathname : '',
    scope: 'openid profile email',
    token: typeof sessionStorage !== 'undefined' ? sessionStorage.getItem(STORAGE_KEYS.accessToken) || '' : '',
    refresh: typeof sessionStorage !== 'undefined' ? sessionStorage.getItem(STORAGE_KEYS.refreshToken) || '' : '',
    expiresAt: typeof sessionStorage !== 'undefined' ? Number(sessionStorage.getItem(STORAGE_KEYS.expiresAt) || 0) : 0,
    authorizationEndpoint: '',
    tokenEndpoint: '',
    logoutEndpoint: '',
  }
}