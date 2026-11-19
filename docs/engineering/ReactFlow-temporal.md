# ReactFlow + QOC Engine — Plan & Analysis

**Status:** Draft (2026-11-10) · Goal: React UI with ReactFlow graph editor + QOC Engine as backend

---

## 1. Goal

A new React-based UI with ReactFlow as the graph editor, using the QOC Engine (not agentflow-app/quad-core) as the backend. Workflows are defined and executed in the ReactFlow format (Nodes + Edges). Agent configuration is performed via an integrated property window inside the graph editor.

---

## 2. Motivation

| Aspect | Current (Angular + agentflow-app) | Target (React + QOC Engine) |
|--------|-----------------------------------|---------------------------|
| UI framework | Angular 20 | React 19 |
| Graph editor | Custom SVG (~700 lines) | ReactFlow (@xyflow/react) |
| Backend | agentflow-app (Spring Boot + quad-core) | Temporal module (plain Java + QOC Engine) |
| Agent runtime | quad-core (mature, 289 tests) | QOC Engine (early stage, runPrompt = placeholder) |
| Storage | In-memory (quad-core) | Memory/SQLite/Postgres per port (QOC) |
| Workflow format | v2 (dependsOn arrays) | ReactFlow (Nodes + Edges) |

---

## 3. Is a "property window for agent config" included in agentflow?

**Partially.** agentflow has two separate views:

| Aspect | agentflow (Angular) | Desired (React) |
|--------|---------------------|-------------------|
| Node properties in the graph editor | ✅ Side panel (ID, Title, Type, Dependencies, Agent-Ref, HITL-Assignee, Loop-Body, Branches, Join-Type) | ✅ Same, as a ReactFlow side panel |
| Agent config (Model, Temperature, SystemPrompt, Tools, Guardrails) | ❌ **Not** in the graph editor. Separate page `/agents` with a form | ✅ **Property window** in the graph editor: select an agent node → edit agent config directly |
| Agent-Ref dropdown | ✅ In the graph editor | ✅ Same, plus inline editing |

**Conclusion:** agentflow has node properties in the graph editor, but agent config lives on a separate page. An **integrated property window** (agent config directly in the graph editor side panel) is **new** and is not part of agentflow.

---

## 4. ReactFlow format vs. agentflow v2 format

### 4.1 agentflow v2 format (dependsOn arrays)

```json
{
  "format": "quad.workflow",
  "schemaVersion": 2,
  "mainWorkflow": {
    "id": "support-triage",
    "name": "Customer Support Triage",
    "nodes": [
      { "id": "classify", "type": "agent", "title": "Classify ticket", "agent": { "ref": "ex-support-classifier" }, "requiresHumanApproval": true },
      { "id": "playbook", "type": "agent", "title": "Find playbook", "dependsOn": ["classify"], "agent": { "ref": "ex-playbook-finder" } },
      { "id": "respond", "type": "agent", "title": "Draft reply", "dependsOn": ["playbook"], "agent": { "ref": "ex-support-writer" } },
      { "id": "end", "type": "end", "title": "Done", "dependsOn": ["respond"] }
    ]
  },
  "agents": [
    { "id": "ex-support-classifier", "name": "Support Classifier", "model": "deepseek-v4.1-flash", "temperature": 0.1, "maxTokens": 1024, "systemPrompt": "..." }
  ]
}
```

**Structure:** Nodes have `dependsOn: string[]` — implicit edges. No explicit edge objects.

### 4.2 ReactFlow format (Nodes + Edges)

```json
{
  "nodes": [
    { "id": "classify", "type": "agent", "position": { "x": 0, "y": 0 }, "data": { "title": "Classify ticket", "agentRef": "ex-support-classifier", "requiresHumanApproval": true } },
    { "id": "playbook", "type": "agent", "position": { "x": 250, "y": 0 }, "data": { "title": "Find playbook", "agentRef": "ex-playbook-finder" } },
    { "id": "respond", "type": "agent", "position": { "x": 500, "y": 0 }, "data": { "title": "Draft reply", "agentRef": "ex-support-writer" } },
    { "id": "end", "type": "end", "position": { "x": 750, "y": 0 }, "data": { "title": "Done" } }
  ],
  "edges": [
    { "id": "e-classify-playbook", "source": "classify", "target": "playbook" },
    { "id": "e-playbook-respond", "source": "playbook", "target": "respond" },
    { "id": "e-respond-end", "source": "respond", "target": "end" }
  ],
  "agents": [
    { "id": "ex-support-classifier", "name": "Support Classifier", "model": "deepseek-v4.1-flash", "temperature": 0.1, "maxTokens": 1024, "systemPrompt": "..." }
  ]
}
```

**Structure:** Explicit nodes with position + edges. ReactFlow-native.

### 4.3 Conversion (ReactFlow ↔ v2)

| ReactFlow | v2 (agentflow) |
|-----------|----------------|
| `node.id` | `node.id` |
| `node.type` | `node.type` |
| `node.data.title` | `node.title` |
| `node.data.agentRef` | `node.agent.ref` |
| `node.data.requiresHumanApproval` | `node.requiresHumanApproval` |
| `edges.filter(e => e.target === node.id).map(e => e.source)` | `node.dependsOn` |
| `node.position` | (not in v2, but in localStorage/DB) |

**Recommendation:** Use the ReactFlow format internally (for the editor), convert to v2 for execution (for the backend). Or: accept the ReactFlow format directly in the backend.

---

## 5. Workflow execution based on the ReactFlow format

### 5.1 How agentflow executes

agentflow uses **LangGraph4j** (Java) for execution. The `DeclarativePlanner` flattens the v2 definition into a graph, and `DeclarativeAgentWorkflow` interprets it.

**Steps:**
1. `DeclarativePlanner.flatten()` — resolve sub-workflows inline
2. `DeclarativeAgentWorkflow.run()` — execute node by node
3. Per node: run the agent (LLM call), evaluate branches, handle loops, wait for HITL
4. Dependencies: `dependsOn` determines the order

### 5.2 How the QOC Engine should execute

The QOC Engine has a `Coordinator` and `ExecutionRunner`, but `runPrompt()` is a placeholder. The execution must be implemented from scratch.

**Approach: ReactFlow graph → QOC Session Engine**

```
ReactFlow JSON (Nodes + Edges)
    ↓
Conversion to v2 (dependsOn from Edges)
    ↓
DeclarativePlanner (learn from agentflow)
    ↓
ExecutionRunner (QOC Coordinator)
    ↓
Per node: QocEngine.runPrompt() (LLM call, once implemented)
```

### 5.3 What can be learned from agentflow

| Component | agentflow | QOC (new) | Action |
|-----------|-----------|-----------|--------|
| DeclarativePlanner | `DeclarativePlanner.java` (flatten, alias) | Adopt | Port (pure Java) |
| ConditionEvaluator | `ConditionEvaluator.java` (contains, equals, regex) | Adopt | Port (pure Java) |
| Node runner | `DeclarativeAgentWorkflow.java` (LangGraph4j) | New | Use QOC Coordinator |
| Agent execution | `AgentBuilder` + `Agent.execute()` | QOC `runPrompt()` | Wire LLM into QOC |
| HITL | Temporal Signal `decide` | Already in HitlWorkflow | Reuse |
| Sub-workflows | Inline flatten | Adopt | Port |

### 5.4 What differs (QOC vs. quad-core)

| Aspect | quad-core (agentflow) | QOC Engine | Consequence |
|--------|----------------------|------------|-------------|
| Agent builder | `AgentBuilder` with ModelFactory, Tools, Guardrails, Hooks | `AgentId` + Session (no builder) | Agent config must be stored as session metadata in QOC |
| LLM call | `Agent.execute()` / `executeStructured()` | `QocEngine.runPrompt()` (placeholder) | **Must be implemented**: ContextBuilder + ProviderAdapter in QOC |
| Tools | ToolRegistry + SseMcpProxy | ToolRuntime (separate) | Wire ToolRuntime into QOC |
| Memory | PersistentMemoryStore + MemoryCategory | SessionStore + JournalStore | Use memory adapter |
| Provenance | ProvenanceTracker | (not available) | Optional: add ProvenanceStore to QOC |
| Guardrails | GuardrailPlugin + PiiAnonymizerHook + AuditTrailHook | PermissionEngine | Use PermissionEngine in QOC |
| Storage | In-memory (quad-core) | Memory/SQLite/Postgres per port | Use QOC storage backends |

---

## 6. Storage in agentflow vs. QOC (in-memory mode)

### 6.1 agentflow (quad-core) storage

agentflow uses quad-core, which stores **in-memory**:
- `PersistentMemoryStore` — in-memory with optional persistence (file/DB)
- `CheckpointService` — in-memory or SQLite (via `CheckpointStore`)
- Session state — in-memory

**No** configurable storage backends per port. Global in-memory or SQLite.

### 6.2 QOC storage (per port)

QOC has **StorageFactory** + **Stores** + **StorageConfig**:

```java
// Per-port configurable
StorageConfig config = new StorageConfig(StorageKind.MEMORY);
// Or: engine.storage.session-store=sqlite
// Or: engine.storage.journal-store=postgres
```

**Ports:**
- `SessionStore` — session metadata
- `JournalStore` — event journal
- `ClaimStore` — distributed claims
- `MemoryStore` — agent memory
- `CheckpointStore` — graph checkpoints
- `ProviderStore` — LLM provider config
- `IntegrationStore` — integrations

**Backends:** MEMORY, SQLITE, POSTGRES (configurable per port)

### 6.3 Differences

| Aspect | agentflow (quad-core) | QOC Engine |
|--------|----------------------|------------|
| Storage granularity | Global (in-memory or SQLite) | Per port (Memory/SQLite/Postgres) |
| Configuration | `agentflow.checkpoint.path` | `engine.storage.<port>=memory\|sqlite\|postgres` |
| Adapter pattern | None (directly in-memory) | StorageFactory + Stores record |
| Scaling | Single node | Multi-node (Postgres) |
| Persistence | Optional (file) | Always (when SQLite/Postgres) |

### 6.4 In-memory mode in QOC

For the Temporal module (in-memory), `StorageKind.MEMORY` is used:

```java
Stores stores = StorageFactory.create(StorageKind.MEMORY, memoryBudget);
StorageConfig config = new StorageConfig(StorageKind.MEMORY);
QocEngine engine = QocEngine.create(stores, config, clock, false);
```

**Consequence:** Data is not persistent. Temporal provides durability via workflow replay. Storage backends are for session state, not workflow state.

---

## 7. Agent config: global vs. defined in AgentConfig

### 7.1 How agentflow handles agent config

In agentflow, agents are defined in the workflow definition as an `agents[]` array:

```json
{
  "agents": [
    {
      "id": "ex-support-classifier",
      "name": "Support Classifier",
      "model": "deepseek-v4.1-flash",
      "temperature": 0.1,
      "maxTokens": 1024,
      "topP": 1.0,
      "tools": [],
      "guardrailsInput": [],
      "guardrailsOutput": [],
      "hooks": [],
      "jsonOutput": true,
      "active": true,
      "systemPrompt": "You classify customer support tickets..."
    }
  ]
}
```

**Agent config is part of the workflow definition.** Each workflow has its own agents. No global agent registry.

### 7.2 How QOC should handle agent config

In QOC there are **no** agent definitions. QOC uses `AgentId` (UUID) and `SessionInfo`. Agent properties (Model, Temperature, etc.) are not part of the session.

**Options:**

| Option | Description | Recommendation |
|--------|-------------|----------------|
| **A: Agent config in the workflow definition** (like agentflow) | Agents as part of the workflow JSON, referenced via `agent.ref` | ✅ Recommended (compatible with agentflow) |
| **B: Global agent registry** | Separate `/agents` endpoint, workflows reference agent IDs | 🟡 For reusable agents |
| **C: Agent config in QOC session** | Agent properties as session metadata | ❌ Session is for runtime state, not config |

**Recommendation: Option A** — agent config in the workflow definition (like agentflow). That is compatible and proven. Optionally add Option B for a global agent registry.

### 7.3 Property window for agent config

In the ReactFlow graph editor, when an agent node is selected:

```
Property Panel (side panel):
├── Node Properties
│   ├── ID: classify
│   ├── Type: agent
│   ├── Title: Classify ticket
│   ├── Agent-Ref: [dropdown: ex-support-classifier]
│   ├── requiresHumanApproval: [checkbox]
│   └── Dependencies: [checkboxes]
│
└── Agent Config (when Agent-Ref is chosen)
    ├── Name: Support Classifier
    ├── Model: [dropdown: deepseek-v4.1-flash, gpt-4o, ...]
    ├── Temperature: [slider: 0.0 - 2.0]
    ├── Max Tokens: [number: 1024]
    ├── Top P: [slider: 0.0 - 1.0]
    ├── System Prompt: [textarea]
    ├── Tools: [multi-select]
    ├── Guardrails Input: [checkboxes: pii, sql-injection, prompt-injection]
    ├── Guardrails Output: [checkboxes: pii, sql-injection, prompt-injection]
    ├── Hooks: [checkboxes: pii-masking, audit]
    └── JSON Output: [checkbox]
```

**Behavior:**
- Select an agent node → property panel shows node properties + agent config
- Change Agent-Ref → agent config is loaded from `agents[]`
- Change agent config → `agents[]` is updated
- "Save" → workflow definition (with agents) is saved

---

## 8. React project plan

### 8.1 Project structure

```
qoc/react-ui/                    # New React project
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── components/
│   │   ├── GraphEditor.tsx      # ReactFlow-based
│   │   ├── PropertyPanel.tsx    # Agent-config property window
│   │   ├── NodeTypes/           # Custom ReactFlow node components
│   │   │   ├── AgentNode.tsx
│   │   │   ├── DecisionNode.tsx
│   │   │   ├── LoopNode.tsx
│   │   │   ├── ForkNode.tsx
│   │   │   ├── JoinNode.tsx
│   │   │   ├── HitlNode.tsx
│   │   │   ├── SubworkflowNode.tsx
│   │   │   └── EndNode.tsx
│   │   ├── Sidebar.tsx
│   │   ├── Topbar.tsx
│   │   └── Toast.tsx
│   ├── pages/
│   │   ├── Dashboard.tsx
│   │   ├── Runs.tsx
│   │   ├── RunDetail.tsx
│   │   ├── Workflows.tsx
│   │   ├── WorkflowDetail.tsx
│   │   ├── Generate.tsx
│   │   ├── Agents.tsx
│   │   ├── Testbench.tsx
│   │   ├── Hitl.tsx
│   │   ├── Observe.tsx
│   │   ├── Examples.tsx
│   │   └── Settings.tsx
│   ├── services/
│   │   ├── api.ts               # REST client (fetch/axios)
│   │   └── runStream.ts         # SSE streaming
│   ├── store/
│   │   └── useWorkflowStore.ts  # Zustand or Jotai
│   ├── types/
│   │   ├── workflow.ts          # v2 + ReactFlow types
│   │   └── agent.ts             # Agent-config type
│   └── utils/
│       ├── convert.ts           # ReactFlow ↔ v2 conversion
│       └── autoLayout.ts        # elkjs integration
```

### 8.2 Dependencies

```json
{
  "dependencies": {
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "react-router-dom": "^7.0.0",
    "@xyflow/react": "^12.0.0",
    "elkjs": "^0.9.0",
    "zustand": "^5.0.0",
    "axios": "^1.7.0",
    "react-hook-form": "^7.50.0",
    "zod": "^3.23.0",
    "tailwindcss": "^4.0.0",
    "@radix-ui/react-dialog": "^1.1.0",
    "@radix-ui/react-select": "^2.1.0",
    "lucide-react": "^0.400.0"
  }
}
```

### 8.3 Property window design

```
┌─────────────────────────────────────────────────────────────┐
│  Graph Editor (ReactFlow Canvas)          │  Property Panel  │
│                                           │                  │
│  ┌──────┐   ┌──────┐   ┌──────┐         │  Node: classify  │
│  │classify│──▶│playbook│──▶│respond│         │  Type: agent     │
│  └──────┘   └──────┘   └──────┘         │  Title: Classify │
│                                           │  Agent: [dropdown]│
│  ┌──────┐                                 │                  │
│  │  end  │                                 │  ┌─ Agent Config ─┐│
│  └──────┘                                 │  │ Model: deepseek ││
│                                           │  │ Temp: 0.1      ││
│                                           │  │ MaxTokens: 1024 ││
│                                           │  │ SystemPrompt:   ││
│                                           │  │ [textarea]      ││
│                                           │  │ Tools: [list]   ││
│                                           │  │ Guardrails:     ││
│                                           │  │ [checkboxes]    ││
│                                           │  └────────────────┘│
│                                           │                  │
│                                           │  [Save] [Reset]  │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. Implementation order

### Phase 1: Set up the React project
1. Vite + React 19 + TypeScript
2. Install ReactFlow (@xyflow/react)
3. Configure Tailwind CSS
4. Basic routing (react-router-dom)
5. API client (axios) with proxy to the Temporal module

### Phase 2: Graph editor with ReactFlow
1. ReactFlow canvas with custom node types
2. Auto-layout (elkjs)
3. Drag & drop, click-to-connect
4. Node CRUD (add, delete, rename)
5. Property panel (side panel) for node properties
6. **Property window for agent config** (Model, Temperature, SystemPrompt, Tools, Guardrails)

### Phase 3: Conversion ReactFlow ↔ v2
1. `convert.ts`: ReactFlow → v2 (edges → dependsOn)
2. `convert.ts`: v2 → ReactFlow (dependsOn → edges)
3. Position persistence (localStorage + backend)

### Phase 4: Backend extension (Temporal module)
1. `POST /workflows/run` — accept ReactFlow JSON
2. Port `DeclarativePlanner` (pure Java)
3. Port `ConditionEvaluator` (pure Java)
4. Implement `QocEngine.runPrompt()` (LLM call)
5. Node runner with QOC Coordinator

### Phase 5: Full UI
1. All 13 pages (Dashboard, Runs, Workflows, etc.)
2. SSE streaming (RunStreamService)
3. Settings, observability, testbench
4. Examples (5 business cases)

---

## 10. What can be learned concretely from agentflow

| agentflow file | What to learn | Port to |
|-----------------|---------------|---------|
| `DeclarativePlanner.java` | Flattening of sub-workflows, alias to `end` | `temporal/.../planner/DeclarativePlanner.java` |
| `ConditionEvaluator.java` | Condition evaluation (contains, equals, regex) | `temporal/.../evaluator/ConditionEvaluator.java` |
| `DeclarativeAgentWorkflow.java` | Node interpretation (agent/decision/loop/fork/join/hitl/end) | `temporal/.../workflow/DeclarativeWorkflow.java` |
| `graph-model.ts` (Angular) | Node-VM mapping, auto-layout | `react-ui/src/utils/convert.ts` |
| `workflow-detail.ts` (Angular) | Graph editor integration, save/validate/version | `react-ui/src/pages/WorkflowDetail.tsx` |
| `agents.ts` (Angular) | Agent editor form | `react-ui/src/components/PropertyPanel.tsx` |
| `models.ts` (Angular) | Type definitions | `react-ui/src/types/workflow.ts` |

---

## 11. Open questions (still to be checked)

1. **QOC Engine LLM wiring:** When will `runPrompt()` be implemented? (ContextBuilder + ProviderAdapter in the QOC Engine)
2. **Agent config in QOC:** Where are agent properties (Model, Temperature, SystemPrompt) stored? In the workflow definition (like agentflow) or in a separate store?
3. **Tool integration:** How are tools (MCP, built-in) integrated into the QOC Engine? ToolRuntime exists but is not coupled to the LLM call.
4. **Guardrails:** How are guardrails (PII, SQL injection, etc.) implemented in the QOC Engine? PermissionEngine exists but is not coupled to agent execution.
5. **Provenance:** Does the QOC Engine need a ProvenanceTracker? (Optional for v1, but important for observability)
6. **Storage in in-memory mode:** Temporal provides durability via workflow replay. But session state (memory, checkpoints) is in-memory in QOC and is lost on restart. Is that acceptable for v1?
7. **ReactFlow ↔ v2 conversion:** Should the backend accept the ReactFlow format directly, or convert internally to v2? (Recommendation: backend accepts the ReactFlow format and converts internally)
8. **Property window scope:** Should the property window show only agent config, or also node properties (dependencies, branches, etc.)? (Recommendation: both, separated into tabs/accordion)

---

## 12. References

- agentflow UI design: `/home/torsten/dev/my-projects/agentflow-standalone/UI.md`
- agentflow Workflow-v2: `/home/torsten/dev/my-projects/agentflow-standalone/agentflow-app/src/main/resources/workflows/support-triage.json`
- QOC Engine: `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/QocEngine.java`
- Temporal module: `qoc/temporal/src/main/java/de/augmentia/qoc/temporal/TemporalServer.java`
- Temporal-Engine.md: `qoc/docs/engineering/Temporal-Engine.md` (§9: AgentFlow analysis, ports)
- Multi-Memory.md: `qoc/docs/engineering/Multi-Memory.md`
- ReactFlow: https://reactflow.dev/
- elkjs: https://github.com/kieler/elkjs
- @xyflow/react: https://www.npmjs.com/package/@xyflow/react