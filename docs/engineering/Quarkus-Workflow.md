# Quarkus Workflow Integration

## Ziel

Workflow-Runner (ReactFlow-basierte Workflows) und die workflow-ui (React) werden in den Quarkus-Stack integriert, sodass ein einziges natives Binary sowohl die Agent-Chat-UI (`/`) als auch den Workflow-Editor (`/workflow`) ausliefert und die Workflow-REST-API unter `/api/workflows*` bereitstellt.

## Architektur (Ziel)

```
┌─────────────────────────────────────────────┐
│  Quarkus Native Binary (port 8080)          │
│                                             │
│  /            → Agent-Chat-UI (index.html)  │
│  /workflow    → Workflow-Editor (React SPA) │
│  /api/*       → JAX-RS (Agents, Sessions,  │
│                 Workflows, Admin, ...)       │
│  /mcp         → MCP Server                  │
│                                             │
│  Shared: QocEngine, WorkflowStore,           │
│          GraphCompiler, WorkflowExecutor     │
└─────────────────────────────────────────────┘
```

## Schritte

### Schritt 1 – Dependency & Bean-Wiring
- `workflow-runner` als Dependency in `quarkus/pom.xml` (slf4j-simple excluden).
- CDI-Beans: `WorkflowStore`, `CheckpointStore` (pfad-konfigurierbar via `engine.workflow.storage-dir`).
- Config: `engine.workflow.storage-dir=${ENGINE_WORKFLOW_STORAGE_DIR:./wf-data}`.

### Schritt 2 – JAX-RS Resource portieren
- `WorkflowResource` unter `/api/workflows` (CRUD, validate, versions, run).
- Nutzt `engine.qocEngine()` + `WorkflowStore`.
- `WorkflowExecutor` pro Request instanziieren.

### Schritt 3 – Native-Reflection (Jackson)
- `@RegisterForReflection` auf `WorkflowDefinition` etc. ODER `reflect-config.json`.
- Test: `mvn -Pnative package` startet ohne Jackson-Fehler.

### Schritt 4 – UI-Assets einbinden (React)
- `npm run build` in `workflow-ui/` → `dist/`.
- Copy nach `quarkus/src/main/resources/META-INF/resources/workflow/`.
- Optional `ui`-Profil im quarkus-pom (frontend-maven-plugin).

### Schritt 5 – SPA-Fallback
- Option A: Hash-Router in React (keine Server-Änderung).
- Option B: JAX-RS `@Path("/workflow/{path:.*}")` → `index.html`.

### Schritt 6 – API-Ausrichtung
- workflow-ui nutzt `baseURL: '/api'` + `/workflows` → passt zu Quarkus.
- Vite-Proxy in Dev auf `:8080`.

### Schritt 7 – Navigation
- Link von `/` → `/workflow` und zurück.

### Schritt 8 – Test
- JVM-Modus: CRUD + Run (Multi-Node) + UI.
- Native-Modus: Binary startet, Assets werden ausgeliefert.

### Schritt 9 – Cleanup
- `WorkflowRunnerMain` als Standalone-Dev-Server behalten (optional).
- Scripts anpassen.

## Status

- [x] Schritt 1 – Dependency & Bean-Wiring
- [x] Schritt 2 – JAX-RS Resource
- [x] Schritt 3 – Native-Reflection
- [x] Schritt 4 – UI-Assets
- [x] Schritt 5 – SPA-Fallback
- [x] Schritt 6 – API-Ausrichtung
- [x] Schritt 7 – Navigation
- [x] Schritt 8 – Test (JVM)
- [x] Schritt 9 – Cleanup

## Risiken

- Jackson-Reflection in Native (häufigste Fehlerquelle).
- SPA-Routing (Fallback).
- WorkflowStore File-I/O im Container (Volume-Mount).
- Shared QocEngine-Instanz (Session-/State-Konsistenz).
