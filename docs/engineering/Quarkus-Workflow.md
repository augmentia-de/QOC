# Quarkus Workflow Integration

## Goal

Integrate the workflow runner (ReactFlow-based workflows) and the workflow-ui (React) into the Quarkus stack, so that a single native binary serves both the Agent-Chat-UI (`/`) and the Workflow Editor (`/workflow`) and exposes the Workflow REST API under `/api/workflows*`.

## Architecture (Target)

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

## Steps

### Step 1 – Dependency & Bean-Wiring
- Add `workflow-runner` as a dependency in `quarkus/pom.xml` (exclude slf4j-simple).
- CDI beans: `WorkflowStore`, `CheckpointStore` (path configurable via `engine.workflow.storage-dir`).
- Config: `engine.workflow.storage-dir=${ENGINE_WORKFLOW_STORAGE_DIR:./wf-data}`.

### Step 2 – Port the JAX-RS Resource
- `WorkflowResource` under `/api/workflows` (CRUD, validate, versions, run).
- Uses `engine.qocEngine()` + `WorkflowStore`.
- Instantiate `WorkflowExecutor` per request.

### Step 3 – Native Reflection (Jackson)
- `@RegisterForReflection` on `WorkflowDefinition` etc. OR `reflect-config.json`.
- Test: `mvn -Pnative package` starts without Jackson errors.

### Step 4 – Include UI Assets (React)
- `npm run build` in `workflow-ui/` → `dist/`.
- Copy into `quarkus/src/main/resources/META-INF/resources/workflow/`.
- Optional `ui` profile in the quarkus pom (frontend-maven-plugin).

### Step 5 – SPA Fallback
- Option A: Hash router in React (no server changes).
- Option B: JAX-RS `@Path("/workflow/{path:.*}")` → `index.html`.

### Step 6 – API Alignment
- workflow-ui uses `baseURL: '/api'` + `/workflows` → matches Quarkus.
- Vite proxy in dev to `:8080`.

### Step 7 – Navigation
- Link from `/` → `/workflow` and back.

### Step 8 – Test
- JVM mode: CRUD + Run (Multi-Node) + UI.
- Native mode: Binary starts, assets are served.

### Step 9 – Cleanup
- Keep `WorkflowRunnerMain` as standalone dev server (optional).
- Adjust scripts.

## Status

- [x] Step 1 – Dependency & Bean-Wiring
- [x] Step 2 – JAX-RS Resource
- [x] Step 3 – Native Reflection
- [x] Step 4 – UI Assets
- [x] Step 5 – SPA Fallback
- [x] Step 6 – API Alignment
- [x] Step 7 – Navigation
- [x] Step 8 – Test (JVM)
- [x] Step 9 – Cleanup

## Risks

- Jackson reflection in native (most common source of errors).
- SPA routing (fallback).
- WorkflowStore file I/O in container (volume mount).
- Shared QocEngine instance (session/state consistency).
"