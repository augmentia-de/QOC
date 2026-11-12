# Workflow – QOC Workflow-Orchestrierung & ReactFlow-Integration

> **Zweck:** Dokumentation, wie AgentFlow die QOC Session Engine als eingebettete
> Java-Bibliothek nutzt (In-Memory-Variante), analog zu `quad-core`. Enthält
> die Umsetzung der [`Multi-Memory.md`](./Multi-Memory.md)-Phasen 0, 1 und B.
>
> **Status:** 2026-11-10 (implementiert in QOC, Integration in AgentFlow ausstehend)
>
> **Referenzen:** [`Multi-Memory.md`](./Multi-Memory.md), [`InMemory.md`](./InMemory.md),
> [`Layering.md`](./Layering.md), [`Storage-alternative.md`](./Storage-alternative.md),
> [`Core.md`](./Core.md).

---

## 1. Architektur-Entscheidung

AgentFlow (Spring Boot + Temporal + LangGraph4j + Spring AI) nutzt QOC nicht als
Service, sondern als **eingebettete Library** (`de.augmentia.qoc:engine`), analog
zu `quad-core`. Die In-Memory-Variante vermeidet DB-Overhead in AgentFlow;
Temporal übernimmt die Durability.

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

## 2. Multi-Memory-Implementierung (Phasen 0, 1, B)

Die Umsetzung folgt [`Multi-Memory.md`](./Multi-Memory.md) und umfasst drei Phasen.

### 2.1 Phase 0: Refactoring (keine Semantik-Änderung)

**Dateien:**

| Datei | Package | Inhalt |
|---|---|---|
| `core/wiring/StorageKind.java` | `core.wiring` | Enum: `MEMORY`, `SQLITE`, `POSTGRES` |
| `core/wiring/Stores.java` | `core.wiring` | Record aller 11 Storage-Ports |
| `core/wiring/StorageConfig.java` | `core.wiring` | Per-Port-Konfiguration (`defaultKind` + Overrides) |
| `adapter/StorageFactory.java` | `adapter` | Einziger Switch: `StorageKind` → Adapter-Instanz |
| `adapter/sql/SqlStores.java` | `adapter.sql` | PostgreSQL-Factory (bündelt `Sql*`-Adapter) |
| `adapter/sqlite/SqliteStores.java` | `adapter.sqlite` | SQLite-Factory (bündelt `Sqlite*`-Adapter) |
| `quarkus/.../Engine.java` | `quarkus.http` | `Engine.wire()` von `boolean sqlite` auf `StorageFactory.create()` umgestellt |

**Kernänderung:** `Engine.wire()` nutzt jetzt `StorageFactory.create(config, ...)` statt
direkter `sqlite ? new SqliteX() : new SqlX()`-Aufrufe. Die `sqlite`-Boolean-Kette
verschwindet.

### 2.2 Phase 1: In-Memory-Adapter

**Package:** `engine/adapter/memory/`

| Datei | Port | Beschreibung |
|---|---|---|
| `RuntimeState.java` | – | Container für alle Maps/Logs, Per-Session-Locks |
| `MemoryBudget.java` | – | Bytes-Accounting, Throws `MemoryLimitExceeded` |
| `MemoryLimitExceeded.java` | – | Typisierter Fehler |
| `MemoryStores.java` | – | Factory: erzeugt alle InMemory-Adapter über `RuntimeState` |
| `InMemorySessionStore.java` | `SessionStore` + `SessionWorkspaceStore` | ConcurrentHashMap, Per-Session-Lock |
| `InMemoryJournal.java` | `Journal` | Append-only Log per Aggregat, gapless Seq |
| `InMemoryConversationStore.java` | `ConversationStore` | ArrayList + AtomicLong Seq |
| `InMemoryClaimStore.java` | `ClaimStore` | `putIfAbsent` für atomares Acquire |
| `InMemoryApprovalStore.java` | `ApprovalStore` | CopyOnWriteArraySet per Projekt |
| `InMemoryInstructionStore.java` | `InstructionStore` | State/Entries/Blobs per Session |
| `InMemoryCredentialStore.java` | `CredentialStore` | ConcurrentHashMap |
| `InMemorySnapshotStore.java` | `SnapshotStore` | Map per Snapshot |
| `InMemoryToolOutputStore.java` | `ToolOutputStore` | Content-addressed Blobs |
| `InMemoryToolResultCache.java` | `ToolResultCache` | LRU-ähnlicher Cache |

**Konfiguration:**

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

**Startup-Guard:** Memory-Modus deaktiviert `DatabaseMigration` und `Recovery`.

### 2.3 Phase B: QocEngine Facade

**Datei:** `engine/runtime/QocEngine.java`

Framework-neutrale Facade, die `StorageFactory` + `Stores` nutzt und ohne
Quarkus/Spring/CDI instanziierbar ist:

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

| Methode | Beschreibung |
|---|---|
| `create(Stores, StorageConfig, Clock, boolean)` | Factory (recoveryEnabled) |
| `stores()` | Zugriff auf alle Ports |
| `storageConfig()` | Per-Port-Konfiguration |
| `clock()` | Zeitquelle |
| `createSession(agent, user, mode)` | Session erstellen |
| `session(id)` | Session lesen |
| `listSessions(user, isAdmin)` | Sessions auflisten |
| `deleteSession(id)` | Session löschen |
| `recover()` | Crash-Recovery (nur durable Backends) |

## 3. Per-Port Storage-Auswahl (Zwischenstufen)

Die Zwischenstufen sind **Konfigurationsprofile**, keine Code-Pfade:

```properties
# Stufe 1: DB + Memory-Cache
engine.storage.default=postgres
engine.storage.tool-cache=memory
engine.storage.tool-outputs=memory
engine.storage.snapshots=memory

# Stufe 2: Redis Hot Tier (später)
engine.storage.default=postgres
engine.storage.journal=redis
engine.storage.sessions=redis
engine.storage.claims=redis
engine.storage.tool-cache=memory

# Stufe 4: Full Memory
engine.storage.default=memory
```

## 4. QOC Engine vs. quad-core in AgentFlow

| Fähigkeit | QOC Engine | quad-core |
|---|---|---|
| Durable Sessions | ✅ | ❌ (In-Memory) |
| Crash-Recovery | ✅ | ❌ |
| Permission-System | ✅ Deny-by-Default | ⚠️ RBAC |
| Tool-Runtime | ✅ Shell/Files/Web/MCP/Subagents | ✅ Built-in Tools |
| Compaction | ✅ Auto-Compaction | ❌ |
| Skills (JIT) | ✅ | ✅ |
| Guardrails | ✅ PermissionEngine | ✅ GuardrailPlugin |
| PII/GDPR | ❌ | ✅ |
| Audit | ✅ Journal (Event-Sourcing) | ✅ AuditTrailHook |
| Saga/Compensation | ✅ Revert/Snapshot | ✅ SagaAgentInterceptor |
| Structured Output | ⚠️ | ✅ |
| Memory (langfristig) | ❌ | ✅ PersistentMemoryStore |
| Provenance | ❌ | ✅ ProvenanceTracker |
| Capability Search/RAG | ❌ | ✅ HNSW/PgVector |
| Orchestrierung | ✅ Coordinator/ExecutionRunner | ❌ (Temporal in AgentFlow) |

**Fazit:** Komplementär, nicht austauschbar. QOC für Sessions/Permissions/Compaction,
quad-core für Capabilities/Skills/PII/Audit/Memory/RAG.

## 5. Abhängigkeit in AgentFlow

```xml
<!-- agentflow-app/pom.xml -->
<dependency>
    <groupId>de.augmentia.qoc</groupId>
    <artifactId>engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

AgentFlow nutzt `QocEngine` als Spring-Bean:

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

## 6. Trennung: Library vs. Service

| Aspekt | Library (embedded) | Service (HTTP) |
|---|---|---|
| Latenz | 0 (in-process) | 10–50 ms |
| Deployment | Ein Prozess | Zwei Prozesse |
| State | Im selben JVM | Separater Prozess |
| Versioning | Maven-Dependency | API-Versionierung |
| Crash-Isolation | Keine (shared JVM) | Vollständig |
| Empfohlen für | Single-Node, ephemere Sessions | Multi-Node, durable Sessions, HA |

Beide Modi werden unterstützt: `MEMORY` für embedded, `POSTGRES`/`SQLITE` für service.

## 7. Nächste Schritte

1. **Phase 2 (Redis):** `engine/adapter/redis/` — nur bei Multi-Node-Bedarf.
2. **Phase 3 (Tiered):** `engine/adapter/tiered/` — optional.
3. **AgentFlow-Integration:** `QocEngine` als Spring-Bean, Session-Lifecycle in Temporal-Activities.
4. **TCK (Contract-Tests):** Dieselbe Semantik für alle Adapter (Memory, SQLite, Postgres).
5. **Maven-Profil `storage-memory`:** Lean Build ohne DB-Extensions.

## 8. Referenzen

| Thema | Pfad |
|---|---|
| Multi-Memory-Plan | [`Multi-Memory.md`](./Multi-Memory.md) |
| In-Memory-Beschreibung | [`InMemory.md`](./InMemory.md) |
| Storage-Analyse | [`Storage-alternative.md`](./Storage-alternative.md) |
| Layering-Entscheidung | [`Layering.md`](./Layering.md) |
| Core-Beschreibung | [`Core.md`](./Core.md) |
| Async/Heartbeat | [`Async-use.md`](./Async-use.md) |
| Task-Orchestrierung | [`Task-Orchestration.md`](./Task-Orchestration.md) |
| Test-Matrix | [`Test.md`](./Test.md) |
| Storage-Ports | `engine/core/port/store/` |
| Adapter (sql) | `engine/adapter/sql/` |
| Adapter (sqlite) | `engine/adapter/sqlite/` |
| Adapter (memory) | `engine/adapter/memory/` |
| StorageFactory | `engine/adapter/StorageFactory.java` |
| Stores | `engine/core/wiring/Stores.java` |
| StorageConfig | `engine/core/wiring/StorageConfig.java` |
| StorageKind | `engine/core/wiring/StorageKind.java` |
| QocEngine Facade | `engine/runtime/QocEngine.java` |
| Engine-Wiring (Quarkus) | `quarkus/http/Engine.java` (Zeile 498–560) |

---

## 9. Workflow-Runner & Workflow-UI (ReactFlow, Option A)

**Status:** 2026-11-10 (implementiert)

### 9.1 Architektur-Entscheidung

Option A: ReactFlow-Format direkt nutzen, kein v2-Format (dependsOn).

- **workflow-runner** (Java, plain Java): HTTP-Server auf Port 8097, ReactFlow-Format (Nodes + Edges)
- **workflow-ui** (React + ReactFlow): Graph-Editor mit Property Panel für Agent-Config
- **QocEngine**: Vollständige LLM-Ausführung (analog zu Quarkus Engine.runPrompt, ohne Security)

### 9.2 workflow-runner Modul

| Komponente | Pfad | Beschreibung |
|---|---|---|
| Datenmodell | `workflow-runner/model/WorkflowDefinition.java` | ReactFlow-Format: Node (id, type, position, data), Edge (id, source, target), Agent |
| Topologische Sortierung | `workflow-runner/runner/TopologicalSort.java` | Kahn-Algorithmus auf Edges, Cycle-Detection |
| HTTP-Server | `workflow-runner/WorkflowRunnerServer.java` | Port 8097: GET /health, POST /workflows/run |
| Execution | WorkflowRunnerServer.run() | Topologie → Node-Execution (agent→QocEngine.runPrompt, end→done) |

**Verifiziert:** Topologische Sortierung a→b→end, Agent-Nodes erstellen Sessions.

### 9.3 workflow-ui Modul (React + ReactFlow)

| Komponente | Pfad | Beschreibung |
|---|---|---|
| Graph-Editor | `workflow-ui/src/pages/GraphEditor.tsx` | ReactFlow Canvas, Custom Node Types (agent, end), Drag & Drop, Click-to-Connect |
| Property Panel | `workflow-ui/src/components/PropertyPanel.tsx` | Agent-Config: Model, Temperature, System Prompt, HITL-Checkbox |
| Types | `workflow-ui/src/types/workflow.ts` | WorkflowNode, WorkflowEdge, Agent, WorkflowDefinition (ReactFlow-Format) |
| Build | Vite + React 19 + TypeScript + Tailwind | `npm run build` erfolgreich, `npm run dev` auf Port 5173 |

### 9.4 QocEngine.runPrompt (vollständige LLM-Ausführung)

- **Wired:** ContextBuilder, Step, ToolRuntime (no-op PermissionGate), ToolSnapshot (leer), ModelInfo, ProviderAdapter (optional)
- **Flow:** Session erstellen → Prompt speichern → Context bauen → Step aufrufen → LLM-Antwort extrahieren
- **Ohne Security:** no-op PermissionGate, leere Tools, keine Permission-Checks
- **Provider:** Optional (HttpProviderAdapter aus OPENAI_API_KEY/OPENAI_BASE_URL/OPENAI_MODEL)
- **Verifiziert:** LLM-Call end-to-end funktioniert (deepseek-v4.1-flash)

### 9.5 QocEngineMain

- Main-Klasse in `temporal/QocEngineMain.java`
- Wired Provider aus .env-Variablen
- Führt Prompt aus und gibt LLM-Antwort zurück
- Script: `scripts/run-qoc-engine-main.sh`

### 9.6 Scripts

| Script | Beschreibung |
|---|---|
| `scripts/run-qoc-engine-main.sh` | Lädt .env, führt QocEngineMain aus |
| `scripts/run-workflow-runner.sh` | Lädt .env, startet WorkflowRunnerServer (Port 8097) |

### 9.7 Agent-Naming: `wf:AgentName:Version`

Workflow-Agenten erhalten den Prefix `wf:` + Name + `:` + Version, um Namenskonflikte
mit QOC-internen Agenten (z.B. "build", "plan") zu vermeiden.

**Regeln:**
1. **Prefix:** `wf:` für alle Workflow-Agenten
2. **Version:** Im Namen enthalten (`wf:Name:Version`), damit verschiedene Versionen koexistieren
3. **Lookup:** Wenn ein Agent mit diesem Namen bereits existiert (Registry/DB), wird die gespeicherte Config genutzt (nicht überschrieben)
4. **Concurrency:** Jede Session hat eine eigene AgentId (UUID) — gleichzeitige Ausführung desselben Agents ist möglich
5. **Fallback:** Wenn kein gespeicherter Agent existiert, wird die übergebene Config verwendet und gespeichert

**Datenfluss:**
```
Workflow Definition → agents: [{ id, name, version, model, systemPrompt, ... }]
  → WorkflowExecutor: prefixedName = "wf:Name:Version"
  → QocEngine.runPrompt(prefixedName, input, AgentConfig)
    → lookupAgent(prefixedName)  // Registry/DB
    → createSession(prefixedName, ...)
    → buildAgentProfile(config) + buildModelInfo(config)
    → Step.run(...)  // LLM-Call mit gespeicherter Config
```

**Warum das bei gleichzeitiger Ausführung funktioniert:**
- Agent-Config ist immutable — wird beim ersten Aufruf gespeichert, nie überschrieben
- Session hat eigene AgentId (UUID) — jede Ausführung ist unabhängig
- Config wird über Namen aufgelöst — nicht über ID
- Kein Locking nötig — Config wird nur gelesen, nie geschrieben nach dem ersten Speichern

### 9.8 Temporal-Integration im Workflow-Runner

**Entscheidung:** Temporal wird direkt in den workflow-runner integriert (Option A).
Der workflow-runner ist die primäre Execution-Engine (ReactFlow-Format) und wird durch
Temporal durable (HITL, Saga, Recovery, Run-Visibility).

#### Architektur

```
workflow-runner (mit Temporal SDK)
├── Temporal Worker (task queue: workflow-runner-queue)
├── ReactFlowWorkflow (@WorkflowInterface)
│   └── Nimmt WorkflowDefinition (ReactFlow) als Input
│   └── Führt topologisch aus (Activities pro Node)
├── Activities: RunAgentActivity, EvaluateConditionActivity, ...
├── QocEngine (in-memory, für Agent-Execution)
├── HTTP-Server (Port 8097): startet Temporal Workflows, Run-Visibility
└── Temporal Signals: decide(boolean) für HITL
```

#### Warum Temporal im Workflow-Runner

1. **Dauerhaftigkeit:** Workflows überleben Crash/Neustart (Temporal Replay)
2. **HITL:** Pause + Resume via Temporal Signals
3. **Saga/Compensation:** Kompensierende Aktionen bei Fehlern
4. **Run-Visibility:** Temporal bietet List/Detail/Events/Terminate out-of-the-box
5. **Streaming:** SSE-Events für Live-Updates

#### Konsolidierung mit temporal-Modul

Langfristig sollten `temporal` und `workflow-runner` zu einem Modul verschmelzen,
da beide QocEngine + Temporal nutzen. Kurzfristig nutzt workflow-runner Temporal
eigenständig. Das `temporal`-Modul wird als "legacy" markiert.

#### Implementierungs-Plan

| Phase | Aufgabe | Aufwand |
|-------|---------|---------|
| 1 | Temporal SDK in workflow-runner, Worker + HTTP-Server | 1 Tag |
| 2 | ReactFlowWorkflow + Activities (RunAgent, Condition, ...) | 3-5 Tage |
| 3 | Durable Execution, Recovery, Run-Visibility | 2-3 Tage |
| 4 | HITL via Signals, Saga/Compensation | 2-3 Tage |
| 5 | Konsolidierung (temporal → workflow-runner) | 2-3 Tage |
| **Gesamt** | | **~10-15 Tage** |

#### Dependencies (workflow-runner pom.xml)

```xml
<!-- Temporal SDK -->
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-sdk</artifactId>
    <version>1.40.0</version>
</dependency>
```

#### ReactFlowWorkflow (Entwurf)

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

### 9.9 Nächste Schritte

1. **workflow-runner:** ConditionEvaluator, DeclarativePlanner, DeclarativeAgentWorkflow portieren
2. **workflow-runner:** WorkflowStore (Persistenz, Versionen), GraphCompiler (Validierung)
3. **workflow-runner:** REST-Endpoints (CRUD, Validate, Save, Versions)
4. **workflow-ui:** Alle Seiten (Dashboard, Runs, Workflows, Agents, etc.)
5. **workflow-ui:** SSE-Streaming, Settings, Observability, Testbench
6. **workflow-ui:** elkjs Auto-Layout, Undo/Redo, Multi-Select
7. **workflow-ui:** Backend-Integration (API-Client auf workflow-runner)
