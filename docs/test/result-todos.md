# E2E-UI-Test: Found Issues and TODOs

**Created:** 2026-11-19
**Sources:** `e2e-ui-test-results.md` (Phase 1), `e2e-ui-phase2-results.md` (Phase 2), `e2e-ui-phase3-results.md` (Phase 3)

---

## 1. Blockers (must be fixed)

### 1.1 SyntaxError on empty 401 response (Runs page)
- **Phase:** 2, 3
- **File:** `reactflow/shared/api.ts` → `request()`
- **Problem:** API returns 401 with an empty body. UI calls `response.json()` → `SyntaxError: Failed to execute 'json' on 'Response': Unexpected end of JSON input`.
- **Fix:** When response is not OK, check whether the body is JSON before calling `response.json()`.
- **Status:** Fixed in shared/api.ts, HITL.tsx, Runs.tsx, SchemaSource.tsx.
- **Priority:** High

### 1.2 Direct navigation to /workflows returns 401
- **Phase:** 2, 3
- **Problem:** `GET /workflows` → 401. Quarkus-Security protects not only `/api/*` but also the SPA fallback.
- **Fix:** SPA fallback (index.html for all non-API paths) should also be served without authentication in OIDC mode.
- **Status:** Fixed in application.properties (public permit order=100, authenticated order=1).
- **Workaround:** Client-side navigation continues to work.
- **Priority:** High

### 1.3 Cert mismatch (test.schippel.info)
- **Phase:** 3 (GCP)
- **Problem:** TLS certificate is for `schippel.info`, not `test.schippel.info`.
- **Fix:** Issue a separate cert (Certbot with `-d test.schippel.info`).
- **Priority:** Medium

---

## 2. UX Improvements

### 2.1 HITL page stays "Loading…"
- **Page:** `/hitl` → show empty state explicitly.
- **Status:** Fixed in HITL.tsx (safe JSON parsing, setRuns([]) on error).

### 2.2 SchemaSource "Generate Schema" stays disabled
- **Page:** Editor → Agent node → Output Contract → "Java source code". Button disabled with prefilled text.
- **Status:** Not a bug – button correctly disabled when textarea is empty. SchemaSource.tsx: added safe fetch with safeFetch().

### 2.3 Auto-Layout with no visible effect
- **Page:** Editor → "Auto-Layout". No effect on unconnected nodes.
- **Status:** Not a bug – ELK layered needs edges for layering. Without edges, nodes remain in a row. Expected behavior.

### 2.4 Testbench Run button disabled without hint
- **Page:** `/testbench`. Tooltip/hint text explaining why disabled.
- **Status:** Fixed in Testbench.tsx (hint text 'Workflow ID required').

### 2.5 Chat file browser shows repo root
- **Page:** `/chat`. Restrict workspace root (ENGINE_WORKSPACE).

### 2.6 Runs page shows "No runs available" after a run
- **Phase:** 3 (GCP, Memory mode). Check whether `/api/workflows/runs` returns runs in Memory mode.

---

## 3. PKCE/Secure Context

### 3.1 PKCE login requires HTTPS or localhost
- Only in test environment (host.docker.internal). Works on localhost/HTTPS.

### 3.2 Logout redirects to localhost (container only)
- Only in Playwright container. Works in host browser.

---

## 4. Token Management

### 4.1 Token expiry (300s Keycloak)
- Implement or test refresh-token flow.

### 4.2 Auth0 token without ROPG (by design)
- No fix needed.

---

## 5. Keycloak Configuration (Phase 2)

### 5.1 Audience mapper missing
- ✅ Fixed (manually). Automate in setup script.

### 5.2 Test user missing
- ✅ Fixed (manually). Automate in setup script.

---

## 6. Tested in Follow-up Session (additional)

### 6.1 Node types (all 16)
- ✅ All 16 node types can be added to the canvas and render correctly.

### 6.2 Undo/Redo
- ✅ Works. Undo removes node, Redo restores it.

### 6.3 Save to file
- ✅ "Saved to data/workflows/e2e-test-run.json".

### 6.4 Auto-Refresh toggle (Runs page)
- ✅ Switches from "OFF" to "ON".

### 6.5 Create agent via UI
- ✅ "+ New agent" opens the Agent Config modal with all fields.

### 6.6 Testbench
- ✅ Run button activates after input. Running on a workflow with an unconnected Fork node returns "Execution failed" with validation error. Correct behavior.

### 6.7 SchemaSource file upload
- ⚠️ Not testable (Playwright container without volume mount). ENOENT.

---

## 7. Not Tested (remaining)

- **Drag-and-drop connections** between nodes – not automatable without coordinates.
- **HITL approval flow** – not tested (allow-all in Phase 1).
- **SchemaSource file upload** (Java/OpenAPI/YAML) – not testable (no volume mount).
- **MCP Server Add/Remove** – only Phase 1 (FAILED on unreachable server).
- **Performance measurement** – only logged.
- **Property panels** of the other 15 node types – not individually exercised.
- **Fork validation on run** – correct (Fork needs ≥2 outgoing edges).

---

## 8. Summary by Priority

### High (Blockers)
1. SyntaxError on empty 401 response (`shared/api.ts`)
2. Direct navigation to /workflows returns 401 (SPA fallback)

### Medium
3. Cert mismatch (test.schippel.info)
4. HITL "Loading…" → empty state
5. Chat file browser repo root (Prod)
6. Runs "No runs" after run
7. Token expiry (Refresh)
8. Automate audience mapper in setup

### Low
9. SchemaSource "Generate Schema" disabled
10. Auto-Layout with no effect
11. Testbench Run button hint
12. PKCE secure context (test only)
13. Logout redirect localhost (test only)
14. Auth0 ROPG (by design)
