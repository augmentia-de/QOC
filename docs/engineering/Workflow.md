# Workflow – QOC Workflow Orchestration & ReactFlow Integration

> **Purpose:** Documentation of how AgentFlow uses the QOC Session Engine as an embedded
> Java library (in-memory variant), analogous to `quad-core`. Contains
> the implementation of the [`Multi-Memory.md`](./Multi-Memory.md) phases 0, 1, and B.
>
> **Status:** 2026-11-10 (implemented in QOC, integration into AgentFlow pending)
>
> **References:** [`Multi-Memory.md`](./Multi-Memory.md), [`InMemory.md`](./InMemory.md),
> [`Layering.md`](./Layering.md), [`Storage-alternative.md`](./Storage-alternative.md),
> [`Core.md`](./Core.md).

---

## 1. Architecture Decision

AgentFlow (Spring Boot + Temporal + LangGraph4j + Spring AI) uses QOC not as a
service but as an **embedded library** (`de.augmentia.qoc:engine`), analogous
to `quad-core`. The in-memory variant avoids DB overhead in AgentFlow;
Temporal handles durability.

```
AgentFlow (Spring Boot)                          QOC Engine (Library)
┌─────────────────────────────────────┐          ┌──────────────────────────────┐
│ Temporal Workflows                  │          │ QocEngine (Facade)           │
│ LangGraph4j Graph                   │  calls   │   ├─ StorageFactory          │
│ Spring AI (Model)                   │ ───────► │   ├─ Stores (SessionStore,   │
│ quad-core (Tools/Skills/Guardrails) │          │   │  Journal, Conversation,  │
│                                     │          │   │  ClaimStore, ...)        │
│ QocEngine Bean (Spring @Configuration) │      │   ├─ Coordinator             │
│                                     │          │   ├─ SessionService         │
└─────────────────────────────────────┘          │   ├─ PermissionEngine       │
                                                 │   └─ ContextBuilder          │
                                                 └──────────────────────────────┘
```

## 2. Multi-Memory Implementation (Phases 0, 1, B)

The implementation follows [`Multi-Memory.md`](./Multi-Memory.md) and comprises three phases.

### 2.1 Phase 0: Refactoring (no semantic change)

**Files:**

| File | Package | Contents |
|---|---|---|
| `core/wiring/StorageKind.java` | `core.wiring` | Enum: `MEMORY`, `SQLITE`, `POSTGRES` |
| `core/wiring/Stores.java` | `core.wiring` | Record of all 11 storage ports |
| `core/wiring/StorageConfig.java` | `core.wiring` | Per-port configuration (`defaultKind` + overrides) |
| `adapter/StorageFactory.java` | `adapter` | Single switch: `StorageKind` → adapter instance |
| `adapter/sql/SqlStores.java` | `adapter.sql` | PostgreSQL factory (bundles `Sql*` adapters) |
| `adapter/sqlite/SqliteStores.java` | `adapter.sqlite` | SQLite factory (bundles `Sqlite*` adapters) |
| `quarkus/.../Engine.java` | `quarkus.http` | `Engine.wire()` switched from `boolean sqlite` to `StorageFactory.create()` |

**Core change:** `Engine.wire()` now uses `StorageFactory.create(config, ...)` instead of
direct `sqlite ? new SqliteX() : new SqlX()` calls. The `sqlite` boolean chain
disappears.

### 2.2 Phase 1: In-Memory Adapter

**Package:** `engine/adapter/memory/`

| File | Port | Description |
|---|---|---|
| `RuntimeState.java` | – | Container for all maps/logs, per-session locks |
| `MemoryBudget.java` | – | Byte accounting, throws `MemoryLimitExceeded` |
| `MemoryLimitExceeded.java` | – | Typed error |
| `MemoryStores.java` | – | Factory: creates all in-memory adapters via `RuntimeState` |
| `InMemorySessionStore.java` | `SessionStore` + `SessionWorkspaceStore` | ConcurrentHashMap, per-session lock |
| `InMemoryJournal.java` | `Journal` | Append-only log per aggregate, gapless seq |
| `InMemoryConversationStore.java` | `ConversationStore` | ArrayList + AtomicLong seq |
| `InMemoryClaimStore.java` | `ClaimStore` | `putIfAbsent` for atomic acquire |
| `InMemoryApprovalStore.java` | `ApprovalStore` | CopyOnWriteArraySet per project |
| `InMemoryInstructionStore.java` | `InstructionStore` | State/entries/blobs per session |
| `InMemoryCredentialStore.java` | `CredentialStore` | ConcurrentHashMap |
| `InMemorySnapshotStore.java` | `SnapshotStore` | Map per snapshot |
| `InMemoryToolOutputStore.java` | `ToolOutputStore` | Content-addressed blobs |
| `InMemoryToolResultCache.java` | `ToolResultCache` | LRU-like cache |

**Configuration:**

```properties
engine.storage.default=memory
engine.recovery.enabled=false
engine.memory.max-sessions=1000
engine.memory.max-bytes=314572800
engine.memory.max-tool-output-bytes=8388608
engine.memory.max-snapshot-bytes=67108864
engine.memory.max-messages-per-session=10000
engine.memory.journal-ring-events=50000
```

**Startup guard:** Memory mode disables `DatabaseMigration` and `Recovery`.

### 2.3 Phase B: QocEngine Facade

**File:** `engine/runtime/QocEngine.java`

Framework-neutral facade that uses `StorageFactory` + `Stores` and is instantiable
without Quarkus/Spring/CDI:

```java
// AgentFlow Spring Boot @Configuration
@Bean
public QocEngine qocEngine() {
    StorageConfig config = new StorageConfig(StorageKind.MEMORY);
    Stores stores = StorageFactory.create(config, null, null,
            new SystemClock(), new NioFileSystem(Path.of(".")));
    return QocEngine.create(stores, config, new SystemClock(), false);
}
```

**Public API:**

| Method | Description |
|---|---|
| `create(Stores, StorageConfig, Clock, boolean)` | Factory (recoveryEnabled) |
| `stores()` | Access to all ports |
| `storageConfig()` | Per-port configuration |
| `clock()` | Time source |
| `createSession(agent, user, mode)` | Create session |
| `session(id)` | Read session |
| `listSessions(user, isAdmin)` | List sessions |
| `deleteSession(id)` | Delete session |
| `recover()` | Crash recovery (durable backends only) |

## 3. Per-Port Storage Selection (intermediate tiers)

The intermediate tiers are **configuration profiles**, not code paths:

```properties
# Tier 1: DB + memory cache
engine.storage.default=postgres
engine.storage.tool-cache=memory
engine.storage.tool-outputs=memory
engine.storage.snapshots=memory

# Tier 2: Redis hot tier (later)
engine.storage.default=postgres
engine.storage.journal=redis
engine.storage.sessions=redis
engine.storage.claims=redis
engine.storage.tool-cache=memory

# Tier 4: Full memory
engine.storage.default=memory
```

## 4. QOC Engine vs. quad-core in AgentFlow

| Capability | QOC Engine | quad-core |
|---|---|---|
| Durable sessions | ✅ | ❌ (in-memory) |
| Crash recovery | ✅ | ❌ |
| Permission system | ✅ Deny-by-default | ⚠️ RBAC |
| Tool runtime | ✅ Shell/Files/Web/MCP/Subagents | ✅ Built-in tools |
| Compaction | ✅ Auto-compaction | ❌ |
| Skills (JIT) | ✅ | ✅ |
| Guardrails | ✅ PermissionEngine | ✅ GuardrailPlugin |
| PII/GDPR | ❌ | ✅ |
| Audit | ✅ Journal (event sourcing) | ✅ AuditTrailHook |
| Saga/compensation | ✅ Revert/snapshot | ✅ SagaAgentInterceptor |
| Structured output | ⚠️ | ✅ |
| Memory (long-term) | ❌ | ✅ PersistentMemoryStore |
| Provenance | ❌ | ✅ ProvenanceTracker |
| Capability search/RAG | ❌ | ✅ HNSW/PgVector |
| Orchestration | ✅ Coordinator/ExecutionRunner | ❌ (Temporal in AgentFlow) |

**Conclusion:** Complementary, not interchangeable. QOC for sessions/permissions/compaction,
quad-core for capabilities/skills/PII/audit/memory/RAG.

## 5. Dependency in AgentFlow

```xml
<!-- agentflow-app/pom.xml -->
<dependency>
    <groupId>de.augmentia.qoc</groupId>
    <artifactId>engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

AgentFlow uses `QocEngine` as a Spring bean:

```java
@Configuration
public class QocEngineConfig {
    @Bean
    public QocEngine qocEngine() {
        StorageConfig config = new StorageConfig(StorageKind.MEMORY);
        Stores stores = StorageFactory.create(config, null, null,
                new SystemClock(), new NioFileSystem(Path.of(".")));
        return QocEngine.create(stores, config, new SystemClock(), false);
    }
}
```

## 6. Separation: Library vs. Service

| Aspect | Library (embedded) | Service (HTTP) |
|---|---|---|
| Latency | 0 (in-process) | 10–50 ms |
| Deployment | One process | Two processes |
| State | Same JVM | Separate process |
| Versioning | Maven dependency | API versioning |
| Crash isolation | None (shared JVM) | Full |
| Recommended for | Single-node, ephemeral sessions | Multi-node, durable sessions, HA |

Both modes are supported: `MEMORY` for embedded, `POSTGRES`/`SQLITE` for service.

## 7. Next Steps

1. **Phase 2 (Redis):** `engine/adapter/redis/` — only if multi-node demand.
2. **Phase 3 (Tiered):** `engine/adapter/tiered/` — optional.
3. **AgentFlow integration:** `QocEngine` as Spring bean, session lifecycle in Temporal activities.
4. **TCK (contract tests):** Same semantics across all adapters (Memory, SQLite, Postgres).
5. **Maven profile `storage-memory`:** Lean build without DB extensions.

## 8. References

| Topic | Path |
|---|---|
| Multi-memory plan | [`Multi-Memory.md`](./Multi-Memory.md) |
| In-memory description | [`InMemory.md`](./InMemory.md) |
| Storage analysis | [`Storage-alternative.md`](./Storage-alternative.md) |
| Layering decision | [`Layering.md`](./Layering.md) |
| Core description | [`Core.md`](./Core.md) |
| Async/heartbeat | [`Async-use.md`](./Async-use.md) |
| Task orchestration | [`Task-Orchestration.md`](./Task-Orchestration.md) |
| Test matrix | [`Test.md`](./Test.md) |
| Storage ports | `engine/core/port/store/` |
| Adapter (sql) | `engine/adapter/sql/` |
| Adapter (sqlite) | `engine/adapter/sqlite/` |
| Adapter (memory) | `engine/adapter/memory/` |
| StorageFactory | `engine/adapter/StorageFactory.java` |
| Stores | `engine/core/wiring/Stores.java` |
| StorageConfig | `engine/core/wiring/StorageConfig.java` |
| StorageKind | `engine/core/wiring/StorageKind.java` |
| QocEngine facade | `engine/runtime/QocEngine.java` |
| Engine wiring (Quarkus) | `quarkus/http/Engine.java` (lines 498–560) |

---

## 9. Workflow Runner & Workflow UI (ReactFlow, Option A)

**Status:** 2026-11-10 (implemented)

### 9.1 Architecture Decision

Option A: Use the ReactFlow format directly, no v2 format (dependsOn).

- **workflow-runner** (Java, plain Java): HTTP server on port 8097, ReactFlow format (Nodes + Edges)
- **workflow-ui** (React + ReactFlow): graph editor with property panel for agent config
- **QocEngine**: Full LLM execution (analogous to Quarkus Engine.runPrompt, without security)

### 9.2 workflow-runner Module

| Component | Path | Description |
|---|---|---|
| Data model | `workflow-runner/model/WorkflowDefinition.java` | ReactFlow format: Node (id, type, position, data), Edge (id, source, target), Agent |
| Topological sort | `workflow-runner/runner/TopologicalSort.java` | Kahn's algorithm on edges, cycle detection |
| HTTP server | `workflow-runner/WorkflowRunnerServer.java` | Port 8097: GET /health, POST /workflows/run |
| Execution | WorkflowRunnerServer.run() | Topology → node execution (agent→QocEngine.runPrompt, end→done) |

**Verified:** Topological sort a→b→end, agent nodes create sessions.

### 9.3 workflow-ui Module (React + ReactFlow)

| Component | Path | Description |
|---|---|---|
| Graph editor | `workflow-ui/src/pages/GraphEditor.tsx` | ReactFlow canvas, custom node types (agent, end), drag & drop, click-to-connect |
| Property panel | `workflow-ui/src/components/PropertyPanel.tsx` | Agent config: model, temperature, system prompt, HITL checkbox |
| Types | `workflow-ui/src/types/workflow.ts` | WorkflowNode, WorkflowEdge, Agent, WorkflowDefinition (ReactFlow format) |
| Build | Vite + React 19 + TypeScript + Tailwind | `npm run build` succeeds, `npm run dev` on port 5173 |

### 9.4 QocEngine.runPrompt (full LLM execution)

- **Wired:** ContextBuilder, Step, ToolRuntime (no-op PermissionGate), ToolSnapshot (empty), ModelInfo, ProviderAdapter (optional)
- **Flow:** Create session → save prompt → build context → invoke Step → extract LLM response
- **Without security:** no-op PermissionGate, empty tools, no permission checks
- **Provider:** Optional (HttpProviderAdapter from OPENAI_API_KEY/OPENAI_BASE_URL/OPENAI_MODEL)
- **Verified:** End-to-end LLM call works (deepseek-v4.1-flash)

### 9.5 QocEngineMain

- Main class in `temporal/QocEngineMain.java`
- Wired provider from .env variables
- Runs a prompt and returns the LLM response
- Script: `scripts/run-qoc-engine-main.sh`

### 9.6 Scripts

| Script | Description |
|---|---|
| `scripts/run-qoc-engine-main.sh` | Loads .env, runs QocEngineMain |
| `scripts/run-workflow-runner.sh` | Loads .env, starts WorkflowRunnerServer (port 8097) |

### 9.7 Agent Naming: `wf:AgentName:Version`

Workflow agents receive the prefix `wf:` + name + `:` + version to avoid name
conflicts with QOC-internal agents (e.g. "build", "plan").

**Rules:**
1. **Prefix:** `wf:` for all workflow agents
2. **Version:** Included in the name (`wf:Name:Version`), so different versions can coexist
3. **Lookup:** If an agent with this name already exists (registry/DB), the stored config is used (not overwritten)
4. **Concurrency:** Each session has its own AgentId (UUID) — concurrent execution of the same agent is possible
5. **Fallback:** If no stored agent exists, the supplied config is used and stored

**Data flow:**
```
Workflow Definition → agents: [{ id, name, version, model, systemPrompt, ... }]
  → WorkflowExecutor: prefixedName = "wf:Name:Version"
  → QocEngine.runPrompt(prefixedName, input, AgentConfig)
    → lookupAgent(prefixedName)  // Registry/DB
    → createSession(prefixedName, ...)
    → buildAgentProfile(config) + buildModelInfo(config)
    → Step.run(...)  // LLM call with stored config
```

**Why this works under concurrent execution:**
- Agent config is immutable — stored on first call, never overwritten
- Session has its own AgentId (UUID) — each execution is independent
- Config is resolved by name — not by ID
- No locking needed — config is only read, never written after first store

### 9.8 Temporal Integration in the Workflow Runner

**Decision:** Temporal is integrated directly into the workflow-runner (Option A).
The workflow-runner is the primary execution engine (ReactFlow format) and becomes
durable through Temporal (HITL, saga, recovery, run visibility).

#### Architecture

```
workflow-runner (with Temporal SDK)
├── Temporal Worker (task queue: workflow-runner-queue)
├── ReactFlowWorkflow (@WorkflowInterface)
│   └── Takes a WorkflowDefinition (ReactFlow) as input
│   └── Executes topologically (activities per node)
├── Activities: RunAgentActivity, EvaluateConditionActivity, ...
├── QocEngine (in-memory, for agent execution)
├── HTTP server (port 8097): starts Temporal workflows, run visibility
└── Temporal signals: decide(boolean) for HITL
```

#### Why Temporal in the Workflow Runner

1. **Durability:** Workflows survive crash/restart (Temporal replay)
2. **HITL:** Pause + resume via Temporal signals
3. **Saga/compensation:** Compensating actions on errors
4. **Run visibility:** Temporal provides list/detail/events/terminate out of the box
5. **Streaming:** SSE events for live updates

#### Consolidation with the temporal module

Long-term, `temporal` and `workflow-runner` should merge into one module,
since both use QocEngine + Temporal. Short-term, workflow-runner uses Temporal
independently. The `temporal` module is marked as "legacy".

#### Implementation Plan

| Phase | Task | Effort |
|-------|------|--------|
| 1 | Temporal SDK in workflow-runner, worker + HTTP server | 1 day |
| 2 | ReactFlowWorkflow + activities (RunAgent, Condition, ...) | 3-5 days |
| 3 | Durable execution, recovery, run visibility | 2-3 days |
| 4 | HITL via signals, saga/compensation | 2-3 days |
| 5 | Consolidation (temporal → workflow-runner) | 2-3 days |
| **Total** | | **~10-15 days** |

#### Dependencies (workflow-runner pom.xml)

```xml
<!-- Temporal SDK -->
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-sdk</artifactId>
    <version>1.40.0</version>
</dependency>
```

#### ReactFlowWorkflow (draft)

```java
@WorkflowInterface
public interface ReactFlowWorkflow {
    @WorkflowMethod
    WorkflowResult run(WorkflowDefinition def, String input);

    @SignalMethod
    void decide(boolean approved);
}

public class ReactFlowWorkflowImpl implements ReactFlowWorkflow {
    private final RunAgentActivity activities = Workflow.newActivityStub(...);

    @Override
    public WorkflowResult run(WorkflowDefinition def, String input) {
        List<String> order = TopologicalSort.sort(def);
        Map<String, String> results = new HashMap<>();
        String context = input;

        for (String nodeId : order) {
            Node node = findNode(def, nodeId);
            switch (node.type()) {
                case "agent" -> {
                    String agentRef = node.data().agentRef();
                    Agent agentDef = findAgent(def, agentRef);
                    String prefixedName = "wf:" + agentDef.name() + ":" + agentDef.version();
                    AgentConfig config = toAgentConfig(agentDef, prefixedName);
                    results.put(nodeId, activities.runAgent(prefixedName, context, config));
                    context = results.get(nodeId);
                }
                case "decision" -> {
                    // Evaluate branches, determine next node
                }
                case "end" -> {
                    // Final result
                }
            }
        }
        return new WorkflowResult(results, context);
    }
}
```

### 9.9 Next Steps

1. **workflow-runner:** Port ConditionEvaluator, DeclarativePlanner, DeclarativeAgentWorkflow
2. **workflow-runner:** WorkflowStore (persistence, versions), GraphCompiler (validation)
3. **workflow-runner:** REST endpoints (CRUD, validate, save, versions)
4. **workflow-ui:** All pages (dashboard, runs, workflows, agents, etc.)
5. **workflow-ui:** SSE streaming, settings, observability, testbench
6. **workflow-ui:** elkjs auto-layout, undo/redo, multi-select
7. **workflow-ui:** Backend integration (API client on workflow-runner)
