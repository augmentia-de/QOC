# Plan: reactflow Workflow-UI — fehlende Menüpunkte & Seiten portieren

## Kontext
- reactflow-Projekt liegt in `qoc/reactflow/` (TypeScript, Zustand, Mobile, SchemaSource, FieldTree).
- Bereits implementiert: Editor (Kern), Validate, Run, Save/Load, Undo/Redo, Output-Contract, Context-Refs, LabeledEdge, Mobile.
- Fehlend: Routing + 10 Seiten (Workflows, Agents, MCP Servers, Generate, Runs, Observe, HITL, Testbench, Settings, Dashboard).
- QOC-Referenz: `qoc/workflow-ui/src/pages/*.tsx` + `qoc/workflow-ui/src/services/api.ts`.

## Ziel
reactflow als vollständigen Ersatz der QOC workflow-ui: alle Menüpunkte, analoge Overlays/Seiten, getestet, deployed.

## Ist-Zustand reactflow
- Single-Page (`App.tsx`), kein Routing.
- Nutzt `Api` (localStorage + optional QOC-API), `QocApi` (validate/run).
- Node-Editor vollständig (Palette, Drag, Connect, Validate, JSON, Agent-Config-Overlay).
- Mobile: NodeListView + BottomSheet (minimal).
- Kein Routing, keine weiteren Seiten.

## Soll-Zustand
- React-Router mit Routes für alle Seiten.
- Top-Nav (Desktop) + BottomNav (Mobile) wie QOC.
- Jede Seite portiert aus QOC, angepasst an reactflow-Styling (inline styles, dunkles Theme).
- Backend-Anbindung über `QocApi` (Auth-Header `X-QOC-User`).

## Phasen

### Phase 1: Routing + Layout (0.5 Tag)
- [ ] `react-router-dom` installieren.
- [ ] `App.tsx` in Layout-Komponente + Routes aufteilen.
- [ ] Top-Nav (Desktop) + BottomNav (Mobile) mit Links zu allen Seiten.
- [ ] Dashboard als Startseite (`/`).
- [ ] Editor als `/editor` (bestehende App-Logik).
- [ ] Workflows als `/workflows` + `/workflows/:id`.

### Phase 2: Workflows-Seite portieren (1 Tag)
- [ ] `src/pages/Workflows.tsx` aus QOC portieren.
- [ ] API: `QocApi.workflows.list/get/create/delete`.
- [ ] "Neuer Workflow" + "Löschen" + Link zu Detail.
- [ ] Detail-Seite (`/workflows/:id`): GraphEditor + JSON-Tab + Validate/Save/Run (wie QOC WorkflowDetail).
- [ ] Auto-Load bei Route-Change.

### Phase 3: Agents-Seite portieren (1 Tag)
- [ ] `src/pages/Agents.tsx` aus QOC portieren.
- [ ] CRUD: list, create, edit, delete via `QocApi.agents`.
- [ ] Agent-Formular: ID, Name, Model, Temperature, MaxTokens, TopP, SystemPrompt, Tools, JSON Output, Response Schema (SchemaSource).
- [ ] Built-in Agents (build/plan) als read-only markieren.

### Phase 4: MCP Servers-Seite portieren (1 Tag)
- [ ] `src/pages/McpServers.tsx` aus QOC portieren.
- [ ] API: listServers, getServerTools, reloadServer, addServer, removeServer.
- [ ] Tool-Preview (expand/collapse mit Tool-Liste).
- [ ] Add-Server-Formular (remote/local).
- [ ] Auto-Refresh alle 30s.

### Phase 5: Generate-Seite portieren (0.5 Tag)
- [ ] `src/pages/Generate.tsx` aus QOC portieren.
- [ ] `POST /workflows/generate` mit Prompt.
- [ ] Ergebnis-Anzeige (Workflow-ID).

### Phase 6: Runs + Observe portieren (1 Tag)
- [ ] `src/pages/Runs.tsx` aus QOC portieren.
- [ ] API: `GET /api/workflows/runs`, `GET /api/workflows/runs/:id`, terminate, SSE-Stream.
- [ ] Auto-Refresh Toggle.
- [ ] `src/pages/Observe.tsx` portieren (Live-Monitoring, 5s Refresh).

### Phase 7: HITL + Testbench portieren (1 Tag)
- [ ] `src/pages/HITL.tsx` portieren.
- [ ] API: `GET /api/session/:id/permission`, `POST .../reply`.
- [ ] `src/pages/Testbench.tsx` portieren.
- [ ] API: `GET /api/workflows/:id`, `POST /api/workflows/run`.

### Phase 8: Settings portieren (0.5 Tag)
- [ ] `src/pages/Settings.tsx` portieren.
- [ ] MCP-Server-Setup + Tool-Preview als eigene Sektion (User-Wunsch #3).
- [ ] Backend-URL, Theme (localStorage).

### Phase 9: Dashboard portieren (0.5 Tag)
- [ ] `src/pages/Dashboard.tsx` portieren.
- [ ] Health-Check, Workflow/Run-Zähler.

### Phase 10: Validierung & Tests (1 Tag)
- [ ] `npm run build` fehlerfrei.
- [ ] `npm run build:qoc` deployed nach quarkus resources.
- [ ] Puppeteer-Smoke-Test: alle Routes laden, CRUD funktioniert.
- [ ] `mvn -pl quarkus package` grün.

### Phase 11: Git (0.5 Tag)
- [ ] Commit pro Phase.
- [ ] Finaler Commit + Push (nur wenn User es anweist).

## Geschätzter Gesamtaufwand
- Phase 1–5: 3–4 Tage (Kern)
- Phase 6–9: 2–3 Tage (Features)
- Phase 10–11: 1 Tag (Test + Deploy)
- **Total: 6–8 Tage**

## Priorisierung (User-Wunsch)
1. Workflows (Load/List + Detail) — bereits teilweise vorhanden (Overlay)
2. Agents — CRUD + SchemaSource
3. MCP Servers + Tool-Preview (auf Settings-Seite)
4. Generate, Runs, Observe, HITL, Testbench, Settings, Dashboard

## Abhängigkeiten
- react-router-dom (neu)
- axios (optional, fetch reicht)
- QOC-Backend muss laufen (8090)

## Offene Fragen
- Soll die alte `workflow-ui/` entfernt werden oder als Fallback bleiben?
- Soll MCP-Server-Setup auf Settings-Seite ODER eigene Seite?
- Soll Testbench in Production oder nur Dev?