# Plan: reactflow Workflow-UI — missing menu items & pages porting

## Context
- reactflow project lives in `qoc/reactflow/` (TypeScript, Zustand, Mobile, SchemaSource, FieldTree).
- Already implemented: Editor (core), Validate, Run, Save/Load, Undo/Redo, Output-Contract, Context-Refs, LabeledEdge, Mobile.
- Missing: Routing + 10 pages (Workflows, Agents, MCP Servers, Generate, Runs, Observe, HITL, Testbench, Settings, Dashboard).
- QOC reference: `qoc/workflow-ui/src/pages/*.tsx` + `qoc/workflow-ui/src/services/api.ts`.

## Goal
reactflow as a complete replacement for the QOC workflow-ui: all menu items, analogous overlays/pages, tested, deployed.

## Current state of reactflow
- Single page (`App.tsx`), no routing.
- Uses `Api` (localStorage + optional QOC-API), `QocApi` (validate/run).
- Node editor fully implemented (Palette, Drag, Connect, Validate, JSON, Agent-Config-Overlay).
- Mobile: NodeListView + BottomSheet (minimal).
- No routing, no further pages.

## Target state
- React Router with routes for all pages.
- Top-Nav (Desktop) + BottomNav (Mobile) as in QOC.
- Each page ported from QOC, adapted to reactflow styling (inline styles, dark theme).
- Backend connection via `QocApi` (Auth header `X-QOC-User`).

## Phases

### Phase 1: Routing + Layout (0.5 day)
- [ ] Install `react-router-dom`.
- [ ] Split `App.tsx` into layout component + routes.
- [ ] Top-Nav (Desktop) + BottomNav (Mobile) with links to all pages.
- [ ] Dashboard as start page (`/`).
- [ ] Editor as `/editor` (existing app logic).
- [ ] Workflows as `/workflows` + `/workflows/:id`.

### Phase 2: Port Workflows page (1 day)
- [ ] Port `src/pages/Workflows.tsx` from QOC.
- [ ] API: `QocApi.workflows.list/get/create/delete`.
- [ ] "New Workflow" + "Delete" + link to detail.
- [ ] Detail page (`/workflows/:id`): GraphEditor + JSON tab + Validate/Save/Run (like QOC WorkflowDetail).
- [ ] Auto-load on route change.

### Phase 3: Port Agents page (1 day)
- [ ] Port `src/pages/Agents.tsx` from QOC.
- [ ] CRUD: list, create, edit, delete via `QocApi.agents`.
- [ ] Agent form: ID, Name, Model, Temperature, MaxTokens, TopP, SystemPrompt, Tools, JSON Output, Response Schema (SchemaSource).
- [ ] Mark built-in agents (build/plan) as read-only.

### Phase 4: Port MCP Servers page (1 day)
- [ ] Port `src/pages/McpServers.tsx` from QOC.
- [ ] API: listServers, getServerTools, reloadServer, addServer, removeServer.
- [ ] Tool preview (expand/collapse with tool list).
- [ ] Add-server form (remote/local).
- [ ] Auto-refresh every 30s.

### Phase 5: Port Generate page (0.5 day)
- [ ] Port `src/pages/Generate.tsx` from QOC.
- [ ] `POST /workflows/generate` with prompt.
- [ ] Result display (Workflow-ID).

### Phase 6: Port Runs + Observe (1 day)
- [ ] Port `src/pages/Runs.tsx` from QOC.
- [ ] API: `GET /api/workflows/runs`, `GET /api/workflows/runs/:id`, terminate, SSE stream.
- [ ] Auto-refresh toggle.
- [ ] Port `src/pages/Observe.tsx` (live monitoring, 5s refresh).

### Phase 7: Port HITL + Testbench (1 day)
- [ ] Port `src/pages/HITL.tsx`.
- [ ] API: `GET /api/session/:id/permission`, `POST .../reply`.
- [ ] Port `src/pages/Testbench.tsx`.
- [ ] API: `GET /api/workflows/:id`, `POST /api/workflows/run`.

### Phase 8: Port Settings (0.5 day)
- [ ] Port `src/pages/Settings.tsx`.
- [ ] MCP server setup + tool preview as its own section (user request #3).
- [ ] Backend URL, theme (localStorage).

### Phase 9: Port Dashboard (0.5 day)
- [ ] Port `src/pages/Dashboard.tsx`.
- [ ] Health check, workflow/run counters.

### Phase 10: Validation & Tests (1 day)
- [ ] `npm run build` error-free.
- [ ] `npm run build:qoc` deployed to quarkus resources.
- [ ] Puppeteer smoke test: all routes load, CRUD works.
- [ ] `mvn -pl quarkus package` green.

### Phase 11: Git (0.5 day)
- [ ] Commit per phase.
- [ ] Final commit + push (only when user instructs).

## Estimated total effort
- Phase 1–5: 3–4 days (core)
- Phase 6–9: 2–3 days (features)
- Phase 10–11: 1 day (test + deploy)
- **Total: 6–8 days**

## Prioritization (user request)
1. Workflows (Load/List + Detail) — partially already present (overlay)
2. Agents — CRUD + SchemaSource
3. MCP Servers + Tool-Preview (on Settings page)
4. Generate, Runs, Observe, HITL, Testbench, Settings, Dashboard

## Dependencies
- react-router-dom (new)
- axios (optional, fetch suffices)
- QOC backend must be running (8090)

## Open questions
- Should the old `workflow-ui/` be removed or kept as fallback?
- Should MCP server setup live on the Settings page OR its own page?
- Should Testbench run in production or dev only?
