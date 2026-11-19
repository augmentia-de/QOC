# E2E UI Test Results – Phase 1 (local, allow-all)

**Date:** 2026-11-19
**Environment:** `qoc-dev-native` (Docker, port 8082), `ENGINE_PERMISSION_DEFAULT=allow-all`, `ENGINE_SECURITY_MODE=none`, real LLM provider (`deepseek-v4.1-flash`).
**Frontend:** ReactFlow (active UI), served by Quarkus under `/`.
**Test method:** Playwright-MCP (automated navigation, clicks, snapshots, console/network checks).

---

## 1. Summary

| Area | Status | Note |
|---|---|---|
| Dashboard | ✅ OK | Backend UP, workflow/run counters |
| Workflows (list) | ✅ OK | 12 workflows, create/delete visible |
| Create workflow | ✅ OK | "e2e-test-run" created |
| Workflow editor | ✅ OK | 16 node types, properties, agent-ref dropdown |
| Validation | ✅ OK | "Workflow is valid! … (3 nodes, 0 agents)" |
| Save | ✅ OK | Status "Saved" |
| Save to file | ⚠️ not tested | Button present, not clicked |
| Run (Start→End) | ✅ OK | Session `ses_8ff…`, status COMPLETED |
| Runs page | ✅ OK | 60+ runs listed, auto-refresh toggle |
| Agents page | ✅ OK | 24+ agents, categorized, edit/delete |
| Engine agents | ✅ OK | build, plan, LLM Eval, LLM Summarize |
| MCP servers | ✅ OK | Add/Remove, FAILED status on unreachable server |
| Settings | ✅ OK | Save → "Saved.", theme, backend URL |
| Testbench | ✅ OK | Workflow ID/input fields, Run button (disabled until input) |
| HITL | ⚠️ "Loading…" | No open permission requests in Phase 1 (expected with allow-all) |
| Chat | ✅ OK | Session/agent selector, file browser, events |
| JSON view | ✅ OK | Full workflow JSON shown |
| SchemaSource (Java) | ✅ OK | File upload + textarea + "Generate schema" |
| Agent config modal | ✅ OK | All fields, tool checkboxes, Save/Save Ref/Reset/Close |
| Console errors | ✅ 0 | No errors/warnings |
| Network errors | ✅ 0 | All API calls 200 OK |
| Backend logs | ✅ OK | Only Quarkus config deprecation warnings |

---

## 2. Tested flows (Phase 1)

### 2.1 Create workflow
- Navigate `/workflows` → textbox "Name of the new workflow" → "e2e-test-run" → "+ New workflow"
- Result: new entry in the list, navigation to the editor.

### 2.2 Editor: add and configure a node
- "Agent" button → Agent node `agent-101` created.
- Agent-ref dropdown shows 27+ agents from the store (incl. `build`).
- Select "build (Build)" → node label becomes "Build", property panel shows "Ref: build · no model".
- Output contract: SchemaSource with tabs Manual/Java/OpenAPI/YAML.

### 2.3 Validation
- Button "Validate" → status: `Workflow is valid! e2e-test-run (3 nodes, 0 agents)`.

### 2.4 Save
- Button "Save" → status: `Saved`.

### 2.5 JSON view
- Button "JSON" → modal with full workflow JSON (nodes, edges, agents, ownerId, inputSchema).
- Agent node contains `systemPrompt` from the agent store (`build`).

### 2.6 Run
- Button "Run" → dialog "Run workflow – initial input" → input "Hello" → "Run".
- Status: `Result: Session ses_8ff9533ad5b4fc179d95856f32ac4cb7 — LLM response: Hello\nOrder:`.

### 2.7 Runs page
- `/runs` → 60+ runs listed, newest run (`ses_8ff…`) with status COMPLETED.

### 2.8 Agent config modal
- "Edit Agent Config" → modal with Name, Version, Category, Model (dropdown: deepseek-v4.1-flash/gpt-4o/…), Temperature, Max Tokens, Top P, Roles, System Prompt, JSON Output, Response Schema (SchemaSource), Tools (23 checkboxes), Guardrails, Hooks, Mode, Skills, Owner ID, Max Turns, Max Cost, Active, Requires Human Approval.
- Buttons: Save, Save Ref, Save ref to file, Reset to Ref, Close.

### 2.9 MCP server Add/Remove
- Add "test-server" → `http://localhost:3000/sse` → status "FAILED (0 tools) – HTTP request failed".
- Remove → list empty again. No crash.

### 2.10 Settings
- Save → feedback "Saved.".

---

## 3. Observations / potential issues

### 3.1 HITL page stays "Loading…"
- **Page:** `/hitl`
- **Behavior:** Shows "Select a run to handle pending permissions." and "Loading…" without further reaction.
- **Context:** In Phase 1 (`allow-all`) there are no permission requests, so an empty result is expected. The "Loading…" indicator may still be misleading when no data is present.
- **Recommendation:** Show an explicit empty state ("No open permission requests").

### 3.2 SchemaSource "Generate schema" stays disabled
- **Page:** Editor → Agent node → Output contract → "Java source code".
- **Behavior:** Textarea is prefilled with example code `public record Person(String name, int age) {}`, button "Generate schema" stays disabled.
- **Possible cause:** Button only activates after the text is changed or a file is uploaded (onChange trigger). Not verified.
- **Recommendation:** Check whether the button should be active when text is already present.

### 3.3 Auto-layout without visible effect
- **Page:** Editor → "Auto-Layout".
- **Behavior:** Click does not visibly change node positions (likely because no edges exist).
- **Recommendation:** Auto-layout should also apply to unconnected nodes, or be disabled.

### 3.4 Chat file browser shows repo root
- **Page:** `/chat` → file browser shows the entire repo root (incl. `.env`, `.git`, `qoc/`).
- **Context:** Acceptable in dev mode; for production (GCP) the workspace should be restricted.
- **Recommendation:** Make the workspace root configurable (already available as `ENGINE_WORKSPACE`).

### 3.5 Testbench Run button disabled until input
- **Page:** `/testbench` → "Run" button is disabled until workflow ID and input are filled in.
- **Assessment:** Correct behavior, but no hint in the UI explaining why the button is disabled.

---

## 4. Not tested (Phase 1)

- **Drag-and-drop connections** between nodes (Start→Agent→End) – not automatable via Playwright without coordinates.
- **HITL approval flow** – no permission requests in Phase 1 (`allow-all`).
- **SchemaSource file upload** (Java/OpenAPI/YAML) – not automated.
- **Creating an agent via the UI** (+ New agent) – not tested.
- **Decision/Fork/Join/Loop/HITL/MCP/REST/Classify/Guard/Inject/Compact/Sub-Workflow/Context-Agent nodes** – only the Agent node was tested in the editor; other node types were not exercised individually.
- **Save to file** – button present, not clicked.
- **Undo/Redo** – not tested.
- **Auto-refresh toggle** on the Runs page – not tested.

---

## 5. Phase 2 & 3 (pending)

- Phase 2: Keycloak-OIDC locally, permission flows, HITL approval.
- Phase 3: GCP Native + Auth0, memory mode, persistence checks.

---

## 6. Console and network checks

- `browser_console_messages` (error + warning, all): **0 messages**.
- `browser_network_requests`: all API calls (`/api/agents`, `/api/workflows/*`, `/api/workflows/validate`) → **200 OK**.
- Backend logs (`docker logs qoc-dev-native`): only Quarkus config deprecation warnings (`quarkus.log.file.json`, `quarkus.log.console.json`), no exceptions.

---

**Conclusion:** Phase 1 (local, allow-all) is fully functional end to end. No blocking errors. The observations above are improvement suggestions, not blockers. The UI is stable and ready for the next phases (Security, GCP).