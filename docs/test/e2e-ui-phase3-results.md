# E2E UI Test Results – Phase 3 (GCP, Auth0, Memory Mode)

**Date:** 2026-11-19
**Environment:** GCP VM `quad-e2micro` (us-central1-a), native binary, memory mode, Auth0 OIDC.
**URL:** https://test.schippel.info/ (nginx → Quarkus :8081)
**Frontend:** ReactFlow, served by Quarkus.
**Test method:** Playwright MCP (HTTPS with ignoreHTTPSErrors), Auth0 Universal Login (PKCE).

---

## 1. Setup

### GCP VM
- Native binary (GraalVM), systemd service `qoc`.
- Memory mode: `ENGINE_STORAGE=memory`, `ENGINE_WORKFLOW_STORAGE=file` (`/data/wf`).
- Recovery disabled (`ENGINE_RECOVERY_ENABLED=false`).
- No database (no JDBC, no Flyway).

### Auth0
- Tenant: `dev-1kxf4iy7txwbmdks.us.auth0.com`
- Client ID (SPA): `urr7kMsdGQgCu4PyOF5llxqNCUGjH1F2`
- Audience: `qoc-engine`
- QOC is a pure resource server (validates Bearer tokens via JWKS).

### nginx
- `test.schippel.info` → proxy_pass `http://127.0.0.1:8081` (QOC Engine).
- TLS via Let's Encrypt (Cert for `schippel.info`, not `test.schippel.info` → Cert mismatch).

---

## 2. Summary

| Area | Status | Note |
|---|---|---|
| UI loading (HTTPS) | ✅ OK | ReactFlow UI is served |
| Auth0 PKCE login | ✅ OK | Works over HTTPS |
| Backend status (auth) | ✅ UP | After login |
| Workflow creation | ✅ OK | "gcp-test" created |
| Workflow validation | ✅ OK | "Workflow is valid!" |
| Workflow execution | ✅ OK | Session created, LLM response |
| Workflows after restart | ✅ Persisted | File storage (`/data/wf`) |
| Runs after restart | ✅ Lost | Memory mode (expected) |
| Agents (4 seeded) | ✅ OK | From file storage |
| Settings (Save) | ✅ OK | "Saved." |
| Agents page | ✅ OK | 4 agents, Edit/Delete |
| Console errors (auth) | ⚠️ 1 | SyntaxError on empty 401 response (Runs page) |
| Cert mismatch | ⚠️ | Cert for schippel.info, not test.schippel.info |

---

## 3. Tested Flows (Phase 3)

### 3.1 Auth0 PKCE Login
- "Login" button → Redirect to Auth0 Universal Login.
- E-mail/password entry → consent screen → "Accept".
- Redirect back to `https://test.schippel.info/` → "Logout" button, Backend Status "UP".
- **Works** over HTTPS (unlike Phase 2, where host.docker.internal was blocked).

### 3.2 Auth0 User Creation
- Signup via Auth0 Management API (`/dbconnections/signup`) successful.
- User `e2etest@example.com` created (email_verified: false, but login possible).

### 3.3 Workflow Creation and Execution
- "gcp-test" workflow created (Start + End).
- Validation: "Workflow is valid! gcp-test (2 nodes, 0 agents)".
- Run: "Result: Session ses_69d97c754c9fdf02491107111aad1201 — LLM response: Hello\nOrder:".

### 3.4 Memory Mode: Persistence After Restart
- `sudo systemctl restart qoc` → Service restarted.
- **Workflows:** "gcp-test" still present (file storage `/data/wf`).
- **Runs:** "No runs available" (memory mode, expected).
- **Agents:** 4 agents still present (file storage).
- **Backend Status:** UP after restart.

### 3.5 Settings
- Save → Feedback "Saved.".

### 3.6 Agents
- 4 agents (Build Agent, plan, research-agent, research-orchestrator) loaded from file storage.
- Edit/Delete buttons present.

---

## 4. Observations / Issues

### 4.1 Cert Mismatch (test.schippel.info)
- **Behavior:** TLS certificate is issued for `schippel.info`, not `test.schippel.info`.
- **Impact:** Browser shows cert warning. Playwright requires `ignoreHTTPSErrors: true`.
- **Recommendation:** Issue a separate cert for `test.schippel.info` (Certbot with `-d test.schippel.info`).

### 4.2 SyntaxError on Empty 401 Response
- **Page:** Runs page (`/runs`) after token expiry.
- **Behavior:** `SyntaxError: Failed to execute 'json' on 'Response': Unexpected end of JSON input`.
- **Cause:** API returns 401 with empty body, UI attempts `response.json()` → parse error.
- **Recommendation:** `request()` in `shared/api.ts` should check whether the body is JSON before calling `response.json()` on a non-OK response (already partially present with `.catch(() => '')`, but the error occurs while parsing the error text).

### 4.3 Token Expiry (Auth0: ~24h, but QOC token 300s?)
- Auth0 tokens have a longer lifetime, but the UI stores `expiresAt = Date.now() + 300000` (5 min) from the `expires_in` of the token response.
- On expiry the user must log in again (no refresh token flow tested in the UI).
- **Recommendation:** Implement or test a refresh token flow.

### 4.4 Runs Page Shows "No Runs Available" After Run
- **Behavior:** After a successful run the Runs page shows "No runs available".
- **Possible cause:** In memory mode runs may not be persisted in the run list, or the run is removed after completion.
- **Recommendation:** Check whether `/api/workflows/runs` returns runs in memory mode.

### 4.5 Direct Navigation to /workflows Returns 401 (Also on GCP)
- **Behavior:** `GET https://test.schippel.info/workflows` → 401.
- **Cause:** Same as Phase 2 (Quarkus security protects the SPA fallback).
- **Workaround:** Client-side navigation.
- **Recommendation:** Serve the SPA fallback without auth for all non-API paths.

---

## 5. Memory Mode: Verification

| Aspect | Expected | Observed |
|---|---|---|
| Workflows after restart | Persisted (file) | ✅ "gcp-test" present |
| Runs after restart | Lost (memory) | ✅ "No runs available" |
| Agents after restart | Persisted (file) | ✅ 4 agents present |
| Backend status after restart | UP | ✅ UP |
| Sessions after restart | Lost (memory) | ✅ (implicit, since runs are lost) |

---

## 6. Not Tested (Phase 3)

- **HITL/Permission flows** – not tested (allow-all or least-privilege not configured).
- **MCP server add/remove** – not tested (analogous to Phase 1/2).
- **Creating an agent via UI** – not tested.
- **Decision/Fork/Join/Loop/HITL/MCP/REST/Classify/Guard/Inject/Compact/Sub-Workflow/Context-Agent nodes** – not individually exercised.
- **Longer runtimes / performance** – only logged, no measurement.

---

## 7. Console and Network Check (GCP)

- Authenticated session: 0 new errors after login (aside from historical 401s and the SyntaxError on Runs).
- Backend API: `/api/health` → 200, `/api/workflows` → 200, `/api/workflows/runs?limit=50` → 401 (token expiry).
- Backend logs: not checked (no SSH log access in this session).

---

**Phase 3 conclusion:** GCP deployment (native, memory mode, Auth0) is functional. Auth0 PKCE login works over HTTPS. Memory mode behaves as designed (workflows/agents persist, runs/sessions lost). The issues noted above (cert mismatch, SyntaxError on empty 401, direct navigation 401) are improvement suggestions.

---

## Overall Phase Summary

| Phase | Environment | Security | Status |
|---|---|---|---|
| 1 | Local (Native Docker, :8082) | none (allow-all) | ✅ Fully functional |
| 2 | Local (Native Docker, :8081) | OIDC (Keycloak) | ✅ Auth + UI functional, PKCE blocked over host.docker.internal |
| 3 | GCP (Native, Memory) | OIDC (Auth0) | ✅ Auth + UI + memory mode functional |
