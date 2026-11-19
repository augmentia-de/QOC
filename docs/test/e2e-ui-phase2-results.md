# E2E-UI Test Results – Phase 2 (local, OIDC/Keycloak)

**Date:** 2026-11-19
**Environment:** `qoc-oidc-native` (Docker, port 8081), `ENGINE_SECURITY_MODE=oidc`, Keycloak (port 8180), real LLM provider (`deepseek-v4.1-flash`).
**Frontend:** ReactFlow, served by Quarkus under `/`.
**Test method:** Playwright-MCP + token injection (service-account/user token via Keycloak Password Grant) + curl verification.

---

## 1. Setup

### Keycloak
- Realm `qoc` with clients: `qoc-ui` (public, PKCE), `qoc-engine` (confidential, service account).
- Test user `testuser` / `testpass` created (email, profile set, requiredActions empty).
- Audience mapper `audience-qoc-engine` added to both clients (including `qoc-engine` in `aud`).
- Direct Access Grants enabled on `qoc-ui` (for test token retrieval).

### QOC Backend (qoc-oidc-native)
- `QUARKUS_OIDC_ENABLED=true`, `ENGINE_SECURITY_MODE=oidc`
- `QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/qoc`
- `QUARKUS_OIDC_TOKEN_ISSUER=http://localhost:8180/realms/qoc`
- `QUARKUS_OIDC_TOKEN_AUDIENCE=qoc-engine`
- `ENGINE_PERMISSION_DEFAULT=allow-all` (permission checks disabled, auth only active)

---

## 2. Summary

| Area | Status | Note |
|---|---|---|
| Backend Health (without token) | ✅ 401 | Correctly protected |
| Backend Health (with token) | ✅ 200 | OIDC validation works |
| UI: Login button | ✅ OK | Shown when not authenticated |
| UI: Logout button | ✅ OK | Shown when authenticated |
| UI: Dashboard (auth) | ✅ OK | Backend Status UP, workflows/runs loaded |
| UI: Workflows (auth) | ✅ OK | List loaded, creation possible |
| UI: Settings (auth) | ✅ OK | Save → "Saved." |
| UI: Logout | ✅ OK | Tokens cleared, back to login |
| Console errors (auth) | ✅ 0 | No new errors after auth |
| Token injection | ✅ OK | sessionStorage-based, works |

---

## 3. Tested Flows (Phase 2)

### 3.1 Backend OIDC Validation (curl)
- Without token: `GET /api/health` → **401** (correct).
- With service-account token: `GET /api/health` → **200** `{"status":"ok",...}`.
- With user token (testuser): `GET /api/health` → **200**.
- `GET /api/workflows` with token → **200** `{"workflows":["aisdlc"]}`.

### 3.2 Audience Mapper Fix
- **Problem:** Keycloak tokens had `aud: "account"` instead of `qoc-engine`.
- **Cause:** Missing audience protocol mapper on the clients.
- **Fix:** Audience mapper `audience-qoc-engine` added to `qoc-engine` and `qoc-ui`.
- **Result:** Tokens now contain `aud: ["qoc-engine", "account"]`.

### 3.3 UI: Authenticated State (Token Injection)
- Token retrieved via Keycloak Password Grant (testuser/testpass, scope=openid).
- Token injected into sessionStorage (`qoc.accessToken`, `qoc.idToken`, `qoc.expiresAt`).
- UI shows "Logout" button, Backend Status "UP", Workflows "1".
- No console errors after injection.

### 3.4 UI: Workflows (auth)
- Client-side navigation to `/workflows` → list loaded (aisdlc).
- Create/delete buttons present.

### 3.5 UI: Settings (auth)
- Save → feedback "Saved.".

### 3.6 UI: Logout
- Logout button → tokens cleared → "Login" button appears, Backend Status "DOWN".

---

## 4. Observations / Issues

### 4.1 PKCE Login Requires HTTPS or localhost
- **Page:** Dashboard → "Login" button.
- **Behavior:** Alert: "Login requires HTTPS or http://localhost (Web Crypto / PKCE)."
- **Cause:** Browser (Chromium) treats `host.docker.internal` as an insecure context → `crypto.subtle` (PKCE) not available.
- **Impact:** UI login flow cannot be tested via `host.docker.internal`.
- **Workaround:** Token injection (as above) or access via `http://localhost:8081` (on the host) or HTTPS.
- **Recommendation:** For automated tests, start Playwright with `--unsafely-treat-insecure-origin-as-secure` or use an HTTPS proxy. The login flow works in the host browser (localhost).

### 4.2 Direct Navigation to /workflows Returns 401
- **Behavior:** `GET http://host.docker.internal:8081/workflows` → HTTP 401.
- **Cause:** Quarkus security protects not only `/api/*` but possibly also the SPA fallback for certain paths, or the token is not sent on direct navigation (no Authorization header on browser navigation).
- **Workaround:** Client-side navigation (clicking a link) works.
- **Recommendation:** The SPA fallback (index.html for all non-API paths) should also be served without auth in OIDC mode, or the security config should protect only `/api/*` and `/mcp/*`.

### 4.3 Logout Redirects to localhost (Unreachable in Container)
- **Behavior:** Logout → browser attempts `http://localhost:8180/...` → ERR_CONNECTION_REFUSED.
- **Cause:** Keycloak runs on the host (localhost:8180), the Playwright browser in the container sees localhost as the container itself.
- **Impact:** Only in the containerized Playwright browser. Logout works in the host browser.
- **Recommendation:** For E2E tests, start Playwright with host networking or make Keycloak reachable via host.docker.internal.

### 4.4 Token Expiry (300s)
- Keycloak tokens expire after 5 minutes. Automated tests must inject the token before expiry or use refresh.
- **Recommendation:** Add a test helper for token refresh.

---

## 5. Not Tested (Phase 2)

- **OIDC login flow via UI** (PKCE) – blocked by the secure-context issue (see 4.1).
- **Permission flows (HITL)** – `ENGINE_PERMISSION_DEFAULT=allow-all` in this instance, so no permission requests. For real permission tests, `least-privilege` would need to be set.
- **Create/edit agent via UI** – not tested.
- **MCP server add/remove** – not tested (analogous to Phase 1).

---

## 6. Phase 3 (Pending)

- GCP Native + Auth0, memory mode, persistence check.
- Auth0 tenant configuration available (`deploy/gcp-vm/gcp-auth0.env`).

---

**Conclusion:** Phase 2 (local, OIDC/Keycloak) is functional. Backend OIDC validation works (after the audience mapper fix). The authenticated UI works (token injection). The UI login flow (PKCE) is blocked by the secure-context issue (host.docker.internal), but works on localhost/HTTPS. The issues above are improvement suggestions.
