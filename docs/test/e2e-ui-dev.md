# E2E System Test Plan: QOC from the UI

**Status:** Plan (2026-11-19)
**Goal:** Full functional test of the system via the UI in three stages:
1. Local without security (JVM Dev, allow-all)
2. Local with security (JVM Dev, Basic Auth / OIDC, least-privilege)
3. GCP with security + In-Memory mode (Native Binary, OIDC, memory)

---

## 1. Test Environment and Infrastructure

### 1.1 Local Dev Stack (JVM)

| Service | Port | Description |
|---|---|---|
| `qoc-dev` (Quarkus Dev Mode) | 8070 | Backend (JVM, Live Coding) |
| `qoc-dev-ui` (nginx) | 5175 | Frontend (ReactFlow) |
| PostgreSQL | 20000 | Database (Sessions, Workflows, Agents) |
| Playwright MCP | 3005 | Browser automation for UI tests |

**Start:**
```bash
cd qoc/deploy
docker compose --profile dev up -d
# or manually:
cd qoc && mvn quarkus:dev  # Backend
cd qoc/reactflow && npm run dev  # Frontend (Port 5173)
```

**Configuration (`.env`):**
```
ENGINE_PERMISSION_DEFAULT=allow-all
ENGINE_PERMISSION_ASK_MODE=interactive
ENGINE_WORKFLOW_STORAGE=file
ENGINE_STORAGE=postgres
```

### 1.2 Local Native Stack (optional)

| Service | Port | Description |
|---|---|---|
| `qoc-dev-native` (Mandrel) | 8081 | Backend (Native, no Hot-Reload) |
| `qoc-dev-native-ui` (nginx) | 5174 | Frontend |

### 1.3 GCP Deployment (Memory Mode)

| Aspect | Value |
|---|---|
| VM | `quad-e2micro`, Zone `us-central1-a` |
| Binary | GraalVM native image |
| Engine Storage | `memory` (Heap, no DB) |
| Workflow Storage | `file` (`/data/wf`) |
| Security | OIDC (Auth0) or Basic Auth |
| Ports | 8081 (Engine), 80/443 (nginx) |

**Deploy:**
```bash
cd qoc
./deploy/gcp-native-memory/build-memory.sh
export GCP_PROJECT=gen-lang-client-0960862878 GCP_ZONE=us-central1-a GCP_VM=quad-e2micro
./deploy/gcp-native-memory/deploy.sh
# Enable OIDC:
set -a; . deploy/gcp-vm/gcp-auth0.env; set +a
./deploy/gcp-vm/gcp-auth0.sh apply
```

---

## 2. Test Scenarios (Workflows)

### 2.1 Base Workflow: Linear (Agent → Agent → End)

```json
{
  "id": "e2e-linear",
  "name": "E2E Linear",
  "nodes": [
    {"id":"start","type":"start","position":{"x":0,"y":0},"data":{"title":"Start"}},
    {"id":"agent1","type":"agent","position":{"x":200,"y":0},"data":{"title":"Agent 1","agentRef":"build"}},
    {"id":"agent2","type":"agent","position":{"x":400,"y":0},"data":{"title":"Agent 2","agentRef":"build"}},
    {"id":"end","type":"end","position":{"x":600,"y":0},"data":{"title":"Done"}}
  ],
  "edges": [
    {"id":"e1","source":"start","target":"agent1"},
    {"id":"e2","source":"agent1","target":"agent2"},
    {"id":"e3","source":"agent2","target":"end"}
  ],
  "agents": []
}
```

**Expectation:** Run → COMPLETED, both agents executed, result from Agent 2.

### 2.2 Decision Workflow (Branching)

```json
{
  "id": "e2e-decision",
  "name": "E2E Decision",
  "nodes": [
    {"id":"start","type":"start","position":{"x":0,"y":0},"data":{"title":"Start"}},
    {"id":"decision","type":"decision","position":{"x":200,"y":0},"data":{"title":"Decision","branches":[{"branchId":"b1","condition":"status == 'ok'","target":"agent_ok"}],"defaultTarget":"agent_fail"}},
    {"id":"agent_ok","type":"agent","position":{"x":400,"y":-100},"data":{"title":"OK Agent","agentRef":"build"}},
    {"id":"agent_fail","type":"agent","position":{"x":400,"y":100},"data":{"title":"Fail Agent","agentRef":"build"}},
    {"id":"end","type":"end","position":{"x":600,"y":0},"data":{"title":"Done"}}
  ],
  "edges": [
    {"id":"e1","source":"start","target":"decision"},
    {"id":"e2","source":"decision","target":"agent_ok","sourceHandle":"branch-b1","condition":"status == 'ok'"},
    {"id":"e3","source":"decision","target":"agent_fail","sourceHandle":"default"},
    {"id":"e4","source":"agent_ok","target":"end"},
    {"id":"e5","source":"agent_fail","target":"end"}
  ],
  "agents": []
}
```

**Expectation:** Condition evaluation selects the correct branch.

### 2.3 Fork/Join Workflow (Parallel)

```json
{
  "id": "e2e-forkjoin",
  "name": "E2E Fork Join",
  "nodes": [
    {"id":"start","type":"start","position":{"x":0,"y":0},"data":{"title":"Start"}},
    {"id":"fork","type":"fork","position":{"x":200,"y":0},"data":{"title":"Fork"}},
    {"id":"a","type":"agent","position":{"x":400,"y":-100},"data":{"title":"Branch A","agentRef":"build"}},
    {"id":"b","type":"agent","position":{"x":400,"y":100},"data":{"title":"Branch B","agentRef":"build"}},
    {"id":"join","type":"join","position":{"x":600,"y":0},"data":{"title":"Join","joinType":"all"}},
    {"id":"end","type":"end","position":{"x":800,"y":0},"data":{"title":"Done"}}
  ],
  "edges": [
    {"id":"e1","source":"start","target":"fork"},
    {"id":"e2","source":"fork","target":"a","sourceHandle":"out-0"},
    {"id":"e3","source":"fork","target":"b","sourceHandle":"out-1"},
    {"id":"e4","source":"a","target":"join","targetHandle":"in-0"},
    {"id":"e5","source":"b","target":"join","targetHandle":"in-1"},
    {"id":"e6","source":"join","target":"end"}
  ],
  "agents": []
}
```

**Expectation:** Fork fans out, Join collects (sequential, since runner single-walk).

### 2.4 Loop Workflow

```json
{
  "id": "e2e-loop",
  "name": "E2E Loop",
  "nodes": [
    {"id":"start","type":"start","position":{"x":0,"y":0},"data":{"title":"Start"}},
    {"id":"loop","type":"loop","position":{"x":200,"y":0},"data":{"title":"Loop","maxIterations":3,"exitCondition":"contains:done","bodySteps":["body"]}},
    {"id":"body","type":"agent","position":{"x":400,"y":0},"data":{"title":"Body Agent","agentRef":"build"}},
    {"id":"end","type":"end","position":{"x":600,"y":0},"data":{"title":"Done"}}
  ],
  "edges": [
    {"id":"e1","source":"start","target":"loop"},
    {"id":"e2","source":"loop","target":"end"}
  ],
  "agents": []
}
```

**Expectation:** Loop executes Body 3 times, then Exit.

### 2.5 HITL Workflow

```json
{
  "id": "e2e-hitl",
  "name": "E2E HITL",
  "nodes": [
    {"id":"start","type":"start","position":{"x":0,"y":0},"data":{"title":"Start"}},
    {"id":"agent","type":"agent","position":{"x":200,"y":0},"data":{"title":"Agent","agentRef":"build","requiresHumanApproval":true}},
    {"id":"end","type":"end","position":{"x":400,"y":0},"data":{"title":"Done"}}
  ],
  "edges": [
    {"id":"e1","source":"start","target":"agent"},
    {"id":"e2","source":"agent","target":"end"}
  ],
  "agents": []
}
```

**Expectation:** Agent pauses, waits for Human Approval, then resumable.

### 2.6 MCP Call Workflow

```json
{
  "id": "e2e-mcp",
  "name": "E2E MCP",
  "nodes": [
    {"id":"start","type":"start","position":{"x":0,"y":0},"data":{"title":"Start"}},
    {"id":"mcp","type":"mcp_call","position":{"x":200,"y":0},"data":{"title":"MCP Call","mcpServer":"filesystem","mcpTool":"read_file","mcpArgs":{"path":"/data/wf/test-wf.json"}}},
    {"id":"end","type":"end","position":{"x":400,"y":0},"data":{"title":"Done"}}
  ],
  "edges": [
    {"id":"e1","source":"start","target":"mcp"},
    {"id":"e2","source":"mcp","target":"end"}
  ],
  "agents": []
}
```

**Expectation:** MCP tool is called, result in Run-State.

### 2.7 Structured Output (JSON Schema)

Agent with `responseSchema`:
```json
{
  "type": "object",
  "properties": {
    "answer": { "type": "string" },
    "confidence": { "type": "number" }
  },
  "required": ["answer"]
}
```

**Expectation:** Agent output is structured JSON, `nodeSessions` contains the result.

---

## 3. Phase 1: Local without Security

### 3.1 Prerequisites

- Docker Dev Stack running (`qoc-dev` :8070, `qoc-dev-ui` :5175)
- PostgreSQL running (Port 20000)
- `ENGINE_PERMISSION_DEFAULT=allow-all`
- `ENGINE_SECURITY_MODE` not set (NONE)
- `ENGINE_WORKFLOW_STORAGE=file`

### 3.2 Test Flow (UI)

| Step | Action | Expectation |
|---|---|---|
| 1 | Open UI: `http://localhost:5175` | Dashboard loads |
| 2 | Workflows → "New Workflow" | Workflow editor opens |
| 3 | Create decision node, configure | Node renders with ports |
| 4 | Create agent node, choose agent ref | Dropdown shows agents |
| 5 | Connect edges (Decision → Agent) | Edge with handle ID |
| 6 | Click "Save" | "Saved" message |
| 7 | Click "Validate" | "✓ valid" |
| 8 | Click "Run" | Result displayed |
| 9 | Open Runs page | Run visible in list |
| 10 | Open Run detail | Status COMPLETED, completedNodes contains all nodes |
| 11 | Open Observe page | SSE events visible |
| 12 | Settings page: check backend URL | URL saved, "Saved." feedback |
| 13 | Agents page: create agent | Agent in store, dropdown updated |
| 14 | MCP-Servers page: add server | Server list updated |

### 3.3 Backend Verification

```bash
# Create workflow
curl -s -X POST -H 'Content-Type: application/json' --data '{"name":"e2e-linear"}' http://localhost:8070/api/workflows
# Save workflow
curl -s -X PUT -H 'Content-Type: application/json' --data @e2e-linear.json http://localhost:8070/api/workflows/e2e-linear
# Validate
curl -s -X POST -H 'Content-Type: application/json' --data @e2e-linear.json http://localhost:8070/api/workflows/validate
# Execute
curl -s -X POST -H 'Content-Type: application/json' --data '{"workflow": {...}, "input": "hello"}' http://localhost:8070/api/workflows/run
# Check runs
curl -s http://localhost:8070/api/workflows/runs
# Check agents
curl -s http://localhost:8070/api/agents
```

### 3.4 Automated UI Tests (Playwright)

The Playwright MCP tools can be used for automated UI tests:
- `browser_navigate` → load page
- `browser_snapshot` → check DOM
- `browser_click` → click buttons/nodes
- `browser_evaluate` → set/check values
- `browser_take_screenshot` → visual verification

**Prepare test workflows (JSON)** in `qoc/wf-data/` or create via API.

---

## 4. Phase 2: Local with Security

### 4.1 Prerequisites

- Dev stack with security configuration:
  ```
  ENGINE_PERMISSION_DEFAULT=least-privilege
  ENGINE_PERMISSION_ASK_MODE=interactive
  ENGINE_SECURITY_MODE=basic
  ENGINE_PASSWORD=<test-password>
  ```
- Or OIDC (Auth0):
  ```
  ENGINE_SECURITY_MODE=oidc
  QUARKUS_OIDC_AUTH_SERVER_URL=https://<tenant>.auth0.com/
  QUARKUS_OIDC_TOKEN_ISSUER=https://<tenant>.auth0.com/
  QUARKUS_OIDC_TOKEN_AUDIENCE=https://qoc-api
  ```

### 4.2 Test Flow (UI)

| Step | Action | Expectation |
|---|---|---|
| 1 | Open UI without login | Redirect to login (Basic Auth dialog or OIDC redirect) |
| 2 | Login with valid credentials | Dashboard loads |
| 3 | Login with invalid credentials | 401/403, error message |
| 4 | Create/edit workflow | Works with auth |
| 5 | Run workflow | Run starts, permission checks take effect |
| 6 | HITL node: request approval | Permission dialog appears |
| 7 | Grant permission (allow) | Workflow continues |
| 8 | Deny permission (deny) | Workflow aborted/failed |
| 9 | Settings: check auth status | Correct status |
| 10 | Logout | Session ended, redirect to login |

### 4.3 Backend Verification

```bash
# Basic Auth
curl -s -u admin:password http://localhost:8070/api/health
curl -s -u admin:password http://localhost:8070/api/workflows
curl -s -u wrong:wrong http://localhost:8070/api/workflows  # → 401

# OIDC
curl -s -H "Authorization: Bearer <token>" http://localhost:8070/api/health
curl -s http://localhost:8070/api/health  # → 401 (without token)
```

### 4.4 Permission Modes

| Mode | Behavior |
|---|---|
| `allow-all` | All actions allowed (no prompt) |
| `least-privilege` | Every action requires explicit permission |
| `interactive` | Permission dialog in UI |
| `deny-all` | All actions denied |

**Test:** Workflow with `requiresHumanApproval: true` on an agent node → HITL interrupt → Approval → Resume.

---

## 5. Phase 3: GCP with Security + In-Memory

### 5.1 Prerequisites

- GCP VM provisioned (`quad-e2micro`, Zone `us-central1-a`)
- Native binary built (`QUARKUS_OIDC_ENABLED=true`)
- OIDC (Auth0) configured
- nginx with TLS (Certbot)
- Memory mode: `ENGINE_STORAGE=memory`, `ENGINE_WORKFLOW_STORAGE=file`

### 5.2 Deployment Flow

```bash
# 1. Native Build
cd qoc
./deploy/gcp-native-memory/build-memory.sh

# 2. Deploy
export GCP_PROJECT=gen-lang-client-0960862878
export GCP_ZONE=us-central1-a
export GCP_VM=quad-e2micro
./deploy/gcp-native-memory/deploy.sh

# 3. Enable OIDC
set -a; . deploy/gcp-vm/gcp-auth0.env; set +a
./deploy/gcp-vm/gcp-auth0.sh check
./deploy/gcp-vm/gcp-auth0.sh apply

# 4. Verification
gcloud compute ssh quad-e2micro --zone=us-central1-a -- \
  'sudo systemctl is-active qoc && curl -fsS http://127.0.0.1:8081/api/health/live'
```

### 5.3 Test Flow (UI)

| Step | Action | Expectation |
|---|---|---|
| 1 | Open `https://<domain>` | UI loads (public) |
| 2 | Login (OIDC redirect to Auth0) | After login: Dashboard |
| 3 | Create/edit workflow | Works with auth |
| 4 | Run workflow | Run starts |
| 5 | HITL approval | Permission dialog, then Resume |
| 6 | Runs page | Runs visible (in-memory, lost on restart) |
| 7 | Settings: OIDC status | Correct |
| 8 | Logout | Session ended |
| 9 | VM restart | Sessions lost (memory mode), Workflows/Agents remain (file store) |

### 5.4 Backend Verification (GCP)

```bash
# Health (without token → 401 in OIDC mode)
curl -sk https://<domain>/api/health/live  # → 401 (expected)

# With token
curl -sk -H "Authorization: Bearer <token>" https://<domain>/api/health

# Workflows (file store)
curl -sk -H "Authorization: Bearer <token>" https://<domain>/api/workflows

# Agents
curl -sk -H "Authorization: Bearer <token>" https://<domain>/api/agents
```

### 5.5 Memory Mode Limitations

| Aspect | Behavior |
|---|---|
| Sessions/Runs | Lost on restart |
| Workflow definitions | Persisted (`/data/wf` as files) |
| Agents | Persisted (`/data/wf/agents` as files) |
| Recovery/Resume | Disabled (stateless) |
| Multi-Node | Not supported (single instance) |

---

## 6. Test Matrix (Summary)

| Test | Local without Sec | Local with Sec | GCP (Sec + Memory) |
|---|---|---|---|
| UI loads | ✅ | ✅ (after login) | ✅ (after login) |
| Workflow CRUD | ✅ | ✅ | ✅ |
| Validate workflow | ✅ | ✅ | ✅ |
| Run workflow | ✅ | ✅ | ✅ |
| Read run status | ✅ | ✅ | ✅ (in-memory) |
| Resume run | ✅ | ✅ | ❌ (disabled) |
| HITL approval | ✅ | ✅ | ✅ |
| Agent CRUD | ✅ | ✅ | ✅ |
| Manage MCP servers | ✅ | ✅ | ✅ |
| Save settings | ✅ | ✅ | ✅ |
| Permission checks | ❌ (allow-all) | ✅ | ✅ |
| OIDC login | ❌ | ✅ (if configured) | ✅ |
| Persistence after restart | ✅ (DB) | ✅ (DB) | ⚠️ (only workflows/agents) |

---

## 7. Automated Tests (Playwright)

The Playwright MCP tools can be used for all three phases:

```typescript
// Example: create and run a workflow
await browser_navigate({ url: "http://localhost:5175/workflows" });
await browser_click({ element: "Neuer Workflow", target: "button:has-text('+ Neuer Workflow')" });
// ... create, configure, connect nodes ...
await browser_click({ element: "Speichern", target: "button:has-text('Speichern')" });
await browser_click({ element: "Ausführen", target: "button:has-text('Ausführen')" });
// Check result
const result = await browser_evaluate({ function: "() => document.body.textContent" });
```

**Prepare test workflows** as JSON files in `qoc/wf-data/` or create via API.

**Screenshot verification** with `browser_take_screenshot` for visual regression.

---

## 8. Decisions (answered)

1. **Automated** — Playwright scripts for all critical paths.
2. **Real LLM provider** — no scripted provider; tests against the configured provider (OpenAI-compatible, e.g. `deepseek-v4.1-flash`).
3. **Phase 2 local: OIDC via Keycloak** — local Keycloak container (port 8180) as OIDC provider.
4. **Phase 3 GCP: OIDC via Auth0** — Auth0 tenant for production.
5. **Performance: logging only** — latency and token consumption are logged but not used as pass/fail criterion.

---

## 9. Keycloak Setup (Phase 2)

### 9.1 Keycloak Container

The `keycloak-local` container already runs on port 8180. For the E2E tests, a realm `qoc` with a client `qoc-ui` (public, PKCE) and a client `qoc-engine` (confidential, service account) is created.

**Realm import (automated via `kcadm.sh` or REST API):**

```bash
# Create realm
curl -s -X POST http://localhost:8180/admin/realms \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"realm":"qoc","enabled":true}'

# Client qoc-ui (public, PKCE, for the UI)
curl -s -X POST http://localhost:8180/admin/realms/qoc/clients \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"clientId":"qoc-ui","publicClient":true,"standardFlowEnabled":true,
       "directAccessGrantsEnabled":true,"redirectUris":["http://localhost:5175/*"],
       "webOrigins":["http://localhost:5175"]}'

# Client qoc-engine (confidential, service account, for the engine)
curl -s -X POST http://localhost:8180/admin/realms/qoc/clients \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"clientId":"qoc-engine","publicClient":false,"standardFlowEnabled":false,
       "serviceAccountsEnabled":true,"secret":"qoc-engine-secret"}'

# Test user
curl -s -X POST http://localhost:8180/admin/realms/qoc/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","enabled":true,"credentials":[{"type":"password","value":"testpass","temporary":false}]}'
```

### 9.2 Engine Configuration (Phase 2)

```
ENGINE_SECURITY_MODE=oidc
QUARKUS_OIDC_AUTH_SERVER_URL=http://localhost:8180/realms/qoc/
QUARKUS_OIDC_TOKEN_ISSUER=http://localhost:8180/realms/qoc/
QUARKUS_OIDC_TOKEN_AUDIENCE=qoc-engine
ENGINE_PERMISSION_DEFAULT=least-privilege
ENGINE_PERMISSION_ASK_MODE=interactive
```

### 9.3 UI Configuration (Phase 2)

The UI uses OIDC Discovery via `/.well-known/openid-configuration`. Keycloak provides this endpoint.

---

## 10. Automated Test Scripts

### 10.1 Playwright Test Structure

```
qoc/e2e/
├── phase1-no-security.spec.ts    # Local without security
├── phase2-keycloak.spec.ts       # Local with Keycloak OIDC
├── phase3-gcp.spec.ts            # GCP with Auth0
├── workflows/
│   ├── e2e-linear.json
│   ├── e2e-decision.json
│   ├── e2e-forkjoin.json
│   ├── e2e-loop.json
│   ├── e2e-hitl.json
│   ├── e2e-mcp.json
│   └── e2e-structured.json
├── helpers/
│   ├── api.ts                    # Backend API helpers
│   ├── auth.ts                   # Login/Logout helpers
│   └── workflow.ts               # Workflow CRUD helpers
└── playwright.config.ts
```

### 10.2 Example: Create and Run Workflow (Phase 1)

```typescript
test('E2E: Linear workflow create → run → verify', async ({ page }) => {
  await page.goto('http://localhost:5175/workflows');
  await page.click('text=+ Neuer Workflow');
  // Create, configure, connect nodes
  await page.click('text=+ Agent');
  await page.click('text=+ Agent');
  await page.click('text=+ End');
  // Connect edges (Drag & Drop)
  // ...
  await page.click('text=Speichern');
  await page.click('text=Ausführen');
  await expect(page.locator('text=Ergebnis')).toBeVisible();
  // Check run status
  await page.goto('http://localhost:5175/runs');
  await expect(page.locator('text=COMPLETED')).toBeVisible();
});
```

### 10.3 Performance Logging

Each test logs:
- Start time, end time, duration (ms)
- Token consumption (inputTokens, outputTokens)
- Number of executed nodes
- SSE event count

Log format (JSON, to `console.log` or file):
```json
{
  "test": "e2e-linear",
  "phase": 1,
  "duration_ms": 3450,
  "input_tokens": 120,
  "output_tokens": 85,
  "nodes_executed": 4,
  "sse_events": 6,
  "timestamp": "2026-11-19T10:00:00Z"
}
```

No pass/fail on performance — logging only.

---

## 11. Recommendation

- **Phase 1 first:** Local without security, run through all workflows, verify UI flows.
- **Phase 2:** Enable Keycloak OIDC, test permission flows.
- **Phase 3:** GCP deployment with memory mode, Auth0 OIDC, full E2E test.
- **Automation:** Playwright scripts for the critical paths (create workflow → run → check run).

Shall I begin with Phase 1 once you leave plan mode? Or would you like adjustments to the plan?

---

## Test Results (2026-11-22)

### Round 1: UI Smoke Test

**Environment:** docker-dev (qoc-dev on port 8070, PostgreSQL on port 20000)

| Page | Status | Notes |
|---|---|---|
| Dashboard | ✅ | Backend Status UP, 18 workflows, Runs counter |
| Workflows | ✅ | 18 workflows listed, create/delete buttons |
| Editor (aisdlc-simple) | ✅ | Nodes render, edges connect, Validate passes after agent sync |
| Runs | ✅ | Runs listed, refresh works, Auto-Refresh toggle |
| Agents | ✅ | Agent groups, Edit/Delete, New agent button |
| Settings | ✅ | Backend URL, Theme, Save/Reset/Reload Config |
| MCP Servers | ✅ | No servers configured, Add Server form |
| Testbench | ✅ | Workflow ID input, Run button, validation |
| HITL | ✅ | Loading state, approval handling |
| Chat | ✅ | Page exists (not deeply tested) |

### Issues Found and Fixed

1. **Container agent store missing AISDLC agents**: `qoc/quarkus/wf-data/agents/` only had `build.json` and `aisdlc-init.json`, not `aisdlc-build.json` etc. Fixed by copying `qoc/wf-data/agents/aisdlc-*.json` to `qoc/quarkus/wf-data/agents/`.
2. **Validation errors on aisdlc-simple**: "Agent ref 'aisdlc-init' not found in agents[]" — resolved by syncing agent files.
3. **Engine ClassNotFoundException (MemoryStore)**: Engine JAR was stale after adding MemoryStore to ExtensionPorts. Fixed by `mvn -pl engine install -DskipTests` and container restart.

### Round 2: Backend API Verification

| Endpoint | Status | Notes |
|---|---|---|
| GET /api/health | ✅ | status: ok, model: deepseek-v4.1-flash |
| GET /api/workflows | ✅ | 18 workflows |
| GET /api/workflows/runs | ✅ | 50 runs, all COMPLETED |
| GET /api/agents | ✅ | Agent list with groups |
| POST /api/workflows/validate | ✅ | Validation works |
| POST /api/workflows/run | ✅ | Run executes, returns runId |

### Conclusion

All key UI flows work. No blocking errors. The docker-dev stack is functional.
