# Temporal-Engine – QOC als Temporal Worker

> **Zweck:** Dokumentation des `temporal`-Moduls: ein Temporal-Server, der
> QOC Engine (In-Memory-Variante) als Agent-Runner nutzt. Basis-Version mit
> einem Step, Planung für UI und erweiterte Features.
>
> **Status:** 2026-11-10 (Grundgerüst implementiert)
>
> **Referenzen:** [`Multi-Memory.md`](./Multi-Memory.md), [`Agentflow-Engine.md`](./Agentflow-Engine.md),
> [`InMemory.md`](./InMemory.md), [`Layering.md`](./Layering.md).

---

## 1. Was ist das `temporal`-Modul?

Ein eigenständiges Maven-Modul unter `qoc/` (nicht unter `agentic-platform/`),
das:

- **Temporal SDK** (1.40.0) nutzt für durable Orchestrierung (Workflows, Activities, Signals, Timer).
- **QOC Engine** (In-Memory-Variante) als Agent-Runner verwendet.
- **Keine Änderungen an `qoc/engine`** vornimmt — nutzt nur die öffentliche `QocEngine`-Facade.
- Ein **einfaches HTTP-Endpoint** (`/run?agent=build&prompt=...`) zum Starten von Workflows bietet.

### Position im Repo

```
qoc/
├─ engine/          # Pure-Java Session Engine (unverändert)
├─ quarkus/         # Quarkus Outer Skin (HTTP/SSE/MCP/A2A)
├─ cli/             # CLI-Tool
└─ temporal/        # NEU: Temporal Server (QOC Engine als Agent-Runner)
```

### Abhängigkeiten

```xml
<dependency>
    <groupId>de.augmentia.qoc</groupId>
    <artifactId>engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-sdk</artifactId>
    <version>1.40.0</version>
</dependency>
```

## 2. Architektur

```
┌─────────────────────────────────────────────────────────────┐
│ Temporal Server (qoc/temporal)                              │
│                                                             │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐   │
│  │ Workflow    │  │ Activity     │  │ QocEngine        │   │
│  │ (QocWorkflow)│  │ (QocActivity)│  │ (In-Memory)      │   │
│  │ one step    │──│ runPrompt()  │──│ Session + Run    │   │
│  └─────────────┘  └──────────────┘  └──────────────────┘   │
│                                                             │
│  HTTP :8096  →  /run?agent=build&prompt=...                │
│  Temporal :7233  (Task Queue: qoc-agent-queue)             │
└─────────────────────────────────────────────────────────────┘
```

### Komponenten

| Komponente | Datei | Beschreibung |
|---|---|---|
| `TemporalServer` | `TemporalServer.java` | Main-Entry, Worker-Factory, HTTP-Endpoint |
| `QocWorkflow` | `workflow/QocWorkflow.java` | Workflow-Interface (ein Step) |
| `QocWorkflowImpl` | `workflow/QocWorkflowImpl.java` | Workflow-Implementation (delegiert an Activity) |
| `QocActivity` | `activity/QocActivity.java` | Activity-Interface |
| `QocActivityImpl` | `activity/QocActivityImpl.java` | Activity-Implementation (nutzt QocEngine) |

## 3. Basis-Version: ein Step

Die Basis-Version hat **einen Step**:

1. Workflow startet → Activity `runPrompt(agent, prompt)` wird aufgerufen.
2. Activity erstellt eine QOC-Session und gibt eine Session-ID + Prompt-Text zurück.
3. Workflow gibt das Ergebnis zurück.

```bash
# Temporal Server starten (benötigt Temporal Dev Server)
cd qoc/temporal
mvn exec:java -Dexec.mainClass=de.augmentia.qoc.temporal.TemporalServer

# Workflow starten
curl "http://localhost:8096/run?agent=build&prompt=Hello"
# → {"result":"Session ses_... created. Prompt queued: Hello"}
```

### Temporal Dev Server

```bash
# Temporal (Docker) starten
cd qoc/deploy
docker compose up -d temporal  # falls vorhanden, oder:
docker run -d --name temporal -p 7233:7233 temporalio/auto-setup:latest
```

## 4. Vergleich mit AgentFlow-Standalone

| Aspekt | AgentFlow-Standalone | QOC Temporal |
|---|---|---|
| Framework | Spring Boot 4.0.8 | Plain Java (kein Spring/Quarkus) |
| Orchestrierung | Temporal 1.40.0 | Temporal 1.40.0 |
| Graph/Reasoning | LangGraph4j 1.9.4 | QOC Step/Drain-Modell |
| LLM-Calls | Spring AI 2.0.1 | QOC Provider-Adapter (LangChain4j) |
| Agent-Basis | `quad-core` | QOC Engine (In-Memory) |
| Session-Engine | SQLite-Checkpoints (LangGraph4j) | QOC SessionStore (In-Memory) |
| Permissions | RBAC via `QuadSecurity` | QOC PermissionEngine (Deny-by-Default) |
| Tools | quad-core built-in + MCP | QOC Tool-Runtime (Shell, Files, Web, MCP, Subagents) |
| UI | Angular 20 | geplant (siehe §5) |
| DB | SQLite + Temporal Postgres | Keine (In-Memory, Temporal übernimmt Durability) |
| Agent-Definitionen | JSON-Workflows (v2) | QOC Agent-Katalog |

### Gemeinsamkeiten

- Beide nutzen Temporal für Durability.
- Beide nutzen `quad-core`-ähnliche Capabilities (Tools, Skills, Guardrails).
- Beide sind separat deploybar.

### Unterschiede

- **AgentFlow** nutzt Spring AI + LangGraph4j; **QOC Temporal** nutzt QOC Engine (LangChain4j).
- **AgentFlow** hat eigene Tools (quad-core); **QOC Temporal** nutzt QOC's Tool-Runtime.
- **AgentFlow** hat UI (Angular); **QOC Temporal** hat noch keine UI.
- **AgentFlow** ist Spring Boot; **QOC Temporal** ist Plain Java.

## 5. UI-Planung (noch nicht implementiert)

### 5.1 Ziel

Eine Web-UI für den Temporal-Server, ähnlich zu AgentFlow-UI, aber mit QOC-Sessions.

### 5.2 Geplante Features

| Feature | Beschreibung | Priorität |
|---|---|---|
| **Run-Liste** | Liste laufender/beendeter Workflows | P0 |
| **Run-Detail** | Session-Info, Messages, Tool-Calls | P0 |
| **Agent-Auswahl** | Dropdown aus QOC Agent-Katalog | P0 |
| **Prompt-Eingabe** | Textarea + Send-Button | P0 |
| **Session-Anlage** | Neuen Workflow starten | P0 |
| **Session-Auswahl** | Bestehende Session laden | P0 |
| **Datei-Operationen** | Dateien lesen/schreiben via QOC Tools | P1 |
| **Workspace-Anzeige** | Aktuelles Workspace-Verzeichnis | P1 |
| **HITL (Approval)** | Freigabe-Dialoge für `ask`-Permissions | P1 |
| **Live-Events (SSE)** | Echtzeit-Updates via QOC's EventBus | P2 |
| **Config-Reload** | Agent/Skill-Konfiguration neu laden | P2 |

### 5.3 Technologie-Vorschlag

| Option | Vorteil | Nachteil |
|---|---|---|
| **Angular** (wie AgentFlow-UI) | Konsistent mit AgentFlow | Separater Build, Node-Dependency |
| **React** (wie QOC's index.html) | Konsistent mit QOC, kein Node-Build | Weniger strukturiert |
| **HTMX/JTE** | Server-rendered, leichtgewichtig | Weniger interaktiv |
| **Vaadin** | Java-only, kein JS | Schwer, Overhead |

**Empfehlung:** React (konsistent mit QOC's bestehender UI) oder Angular (konsistent mit AgentFlow). Für den Start: einfache HTML/JS-Seite (wie QOC's `index.html`).

### 5.4 UI-Architektur

```
temporal/
├─ src/main/resources/
│  └─ META-INF/resources/
│     └─ index.html          # Einfache UI (wie QOC)
├─ src/main/java/.../
│  └─ HttpServer.java        # Java HttpServer (bereits in TemporalServer)
```

Die UI kommuniziert mit dem Temporal-Server über HTTP (`/run`, `/health`, `/sessions`).

## 6. Erweiterte Features (geplant)

| Feature | Beschreibung | Priorität |
|---|---|---|
| **Multi-Step Workflows** | Workflows mit mehreren Activities (Plan → Execute → Review) | P1 |
| **HITL (Human-in-the-Loop)** | Temporal Signals für Freigaben | P1 |
| **Saga/Compensation** | Kompensation bei Fehlern | P2 |
| **Persistent Checkpoints** | SQLite für Graph-State (wie AgentFlow) | P2 |
| **Streaming (SSE)** | Echtzeit-Events vom QOC EventBus | P2 |
| **MCP-Tools** | QOC's MCP-Server als Tool-Provider | P2 |
| **Subagent-Delegation** | QOC's Subagent-System in Activities | P3 |
| **Workspace-Management** | Datei-Operationen via QOC Tools | P1 |

## 7. Start und Deployment

### Lokal starten

```bash
# 1. Temporal Dev Server
docker run -d --name temporal -p 7233:7233 temporalio/auto-setup:latest

# 2. Temporal Module bauen
cd qoc
mvn -pl temporal -am package -DskipTests

# 3. Temporal Server starten
java -jar temporal/target/temporal-1.0.0-SNAPSHOT.jar

# 4. Workflow starten
curl "http://localhost:8096/run?agent=build&prompt=Hello"
```

### Docker

```bash
# TODO: Dockerfile für temporal-Modul
```

### GCP

```bash
# TODO: Native Binary + systemd (wie QOC)
```

## 8. Offene Fragen

1. Soll der Temporal-Server als **eigenständiger Prozess** laufen oder als **Modul in QOC**?
2. Soll die UI **Angular** (AgentFlow-Stil) oder **React/HTML** (QOC-Stil) sein?
3. Soll **QOC als Service** (HTTP) oder **als Library** (embedded) genutzt werden?
4. Wie sollen **Sessions** zwischen Temporal-Workflows und QOC persistiert werden?
5. Soll **Keycloak/Auth0** für die Temporal-UI genutzt werden?

## 9. AgentFlow-Standalone: Workflow-Eigenschaften und Funktionen

Analyse von `/home/torsten/dev/my-projects/agentflow-standalone` — alle Workflow-Eigenschaften
und Funktionen, die in QOC aktuell **nicht** vorhanden sind. Diese werden als
**Interface/Port** für spätere Adapter-Bereitstellung geplant (keine Implementierung jetzt).

### 9.1 Workflow-Eigenschaften (AgentFlow hat, QOC nicht)

| # | Eigenschaft | AgentFlow | QOC Status | Port/Interface (geplant) |
|---|---|---|---|---|
| 1 | **Graph-Reasoning** (StateGraph, conditional edges) | LangGraph4j `StateGraph`, `addConditionalEdges` | ❌ QOC hat Step/Drain, kein Graph | `GraphEngine` port |
| 2 | **HITL (Human-in-the-Loop)** | `interruptBefore(REVIEWER)`, Signal `decide` | ⚠️ QOC hat Approval-System, aber kein Graph-Pause | `HumanDecisionPort` |
| 3 | **Persistent Checkpoints** | `SQLiteSaverV2`, worker-restart-safe | ❌ QOC hat Journal (Event-Sourcing), aber kein Graph-Checkpoint | `CheckpointStore` port |
| 4 | **Run-Listing/Detail/Events/Terminate** | `RunsController`, `RunViews`, `RunStreamController` | ⚠️ QOC hat Sessions, aber keine Temporal-Run-Views | `RunVisibilityPort` |
| 5 | **Saga/Compensation** | `SagaWorkflow`, forward steps + compensations | ⚠️ QOC hat `Revert`/`SnapshotStore`, aber keine Saga | `SagaPort` |
| 6 | **Structured Output** | `withStructuredOutput(ContentPlan)` | ⚠️ QOC über Provider, kein typisiertes Schema | `StructuredOutputPort` |
| 7 | **Provenance** | `ProvenanceTracker`, `ProvenanceEntry` | ❌ QOC hat Journal (Events), aber keine Tool-Provenance | `ProvenancePort` |
| 8 | **Long-term Memory** | `PersistentMemoryStore`, `MemoryEntry`, `remember/recall` | ❌ QOC hat nur Session-History | `LongTermMemoryPort` |
| 9 | **MCP over SSE** | `SseMcpProxy`, list/call tools | ⚠️ QOC hat MCP-Server, aber kein MCP-Client in Temporal | `McpClientPort` |
| 10 | **Sub-agents** | `SubAgentTool`, `DynamicSubAgentTool` | ✅ QOC hat `SubagentTool`, `BackgroundTasks` | (bereits in QOC) |
| 11 | **Metrics/OTel** | `MetricsHook`, `TracingHook`, Micrometer | ⚠️ QOC über Quarkus, nicht in Temporal | `MetricsPort` |
| 12 | **Capability Search/RAG** | `CapabilitySearch`, HNSW/PgVector | ❌ QOC hat keinen Capability-Index | `CapabilitySearchPort` |
| 13 | **Declarative Workflows (v2)** | JSON-Workflow-Definitionen, `Branch`, `ConditionEvaluator` | ❌ QOC hat keine deklarativen Workflows | `DeclarativeWorkflowPort` |
| 14 | **Dynamic Workflows** | `WorkflowDefinitionLoader`, `WorkflowDefinitionRegistry` | ❌ QOC hat keine dynamischen Workflows | `DynamicWorkflowPort` |
| 15 | **Workflow Generator** | `WorkflowGeneratorService` | ❌ | `WorkflowGeneratorPort` |
| 16 | **Streaming (SSE)** | `RunStreamController` | ⚠️ QOC hat SSE, aber nicht für Temporal-Runs | `RunStreamPort` |
| 17 | **Settings/Config CRUD** | `SettingsController` | ⚠️ QOC hat `/api/config`, aber anderes Modell | `SettingsPort` |
| 18 | **Capability Catalog** | `CapabilityController`, `CapabilityBootstrap` | ❌ QOC hat Agent-Katalog, aber keinen Capability-Index | `CapabilityCatalogPort` |
| 19 | **RBAC (role-based tools)** | `QuadSecurity`, `RoleBasedToolGuard` | ⚠️ QOC hat PermissionEngine, aber anderes Modell | `RbacPort` |
| 20 | **Saga (durable)** | `SagaWorkflow`, forward + compensation | ⚠️ QOC hat `Revert`/`SnapshotStore` | `SagaPort` |
| 21 | **Structured Output** | `withStructuredOutput(ContentPlan)` | ⚠️ QOC über Provider, kein typisiertes Schema | `StructuredOutputPort` |
| 22 | **Long-term Memory** | `PersistentMemoryStore`, `remember/recall` | ❌ QOC hat nur Session-History | `LongTermMemoryPort` |
| 23 | **Provenance** | `ProvenanceTracker`, `ProvenanceEntry` | ❌ QOC hat Journal (Events), aber keine Tool-Provenance | `ProvenancePort` |

### 9.2 QOC-eigene Funktionen (nicht in AgentFlow)

| # | Funktion | QOC | AgentFlow |
|---|---|---|---|
| 1 | **Session Engine** (Inbox, Delivery, Compaction, Revert) | ✅ | ❌ |
| 2 | **Permission-System** (Deny-by-Default, Rules, Approvals) | ✅ | ⚠️ RBAC |
| 3 | **Crash-Recovery** (Claim-basiert) | ✅ | ⚠️ Temporal Replay |
| 4 | **Context-Compaction** | ✅ | ❌ |
| 5 | **MCP-Server** (JSON-RPC) | ✅ | ❌ (nur Client) |
| 6 | **A2A-Protokoll** | ✅ | ❌ |
| 7 | **Subagent-System** (BackgroundTasks, Registry) | ✅ | ⚠️ SubAgentTool |
| 8 | **Instructions** (per Session) | ✅ | ❌ |
| 9 | **Snapshots/Revert** | ✅ | ❌ |
| 10 | **Tool-Output-Store** | ✅ | ❌ |

### 9.3 Ports für spätere Adapter-Bereitstellung

Die folgenden Port-Interfaces sollen in `temporal/` definiert werden, um die
AgentFlow-Funktionen als spätere Adapter bereitzustellen. Implementierungen
kommen später.

```java
// temporal/src/main/java/.../port/

/** Graph-Reasoning Engine (LangGraph4j-Äquivalent). */
public interface GraphEngine {
    CompiledGraph buildGraph(NodeHandler planner, NodeHandler executor, NodeHandler reviewer, CompileConfig config);
    Map<String, Object> runGraph(String input, Map<String, Object> initialState);
}

/** Human-in-the-Loop: Pause + Signal für Freigaben. */
public interface HumanDecisionPort {
    void pause(String runId, String reason);
    void decide(String runId, boolean approved);
    boolean isPaused(String runId);
}

/** Persistent Checkpoints für Graph-State. */
public interface CheckpointStore {
    void save(String graphId, String checkpointId, Map<String, Object> state);
    Optional<Map<String, Object>> load(String graphId, String checkpointId);
    List<String> listCheckpoints(String graphId);
}

/** Run-Visibility: Listing, Detail, Events, Terminate. */
public interface RunVisibilityPort {
    List<RunSummary> list(String query, int limit);
    RunDetail detail(String runId);
    List<RunEvent> events(String runId, int max);
    void terminate(String runId, String reason);
}

/** Saga/Compensation: Forward-Steps + Kompensationen. */
public interface SagaPort {
    void registerCompensation(String runId, String stepName, Runnable compensation);
    void unwind(String runId);
}

/** Structured Output: typisiertes Schema. */
public interface StructuredOutputPort {
    <T> T executeStructured(String prompt, Class<T> schema);
}

/** Provenance: Herkunft/Nachverfolgung von Tool-Ergebnissen. */
public interface ProvenancePort {
    void track(String runId, String toolName, String input, String result);
    List<ProvenanceEntry> entries(String runId);
}

/** Long-term Memory: remember/recall über Scopes. */
public interface LongTermMemoryPort {
    void remember(String scope, String content);
    List<String> recall(String scope, String query);
}

/** MCP-Client (SSE): Tools von externen MCP-Servern laden/aufrufen. */
public interface McpClientPort {
    List<McpTool> listTools(String serverUrl);
    String callTool(String serverUrl, String toolName, Map<String, Object> args);
}

/** Metrics/OTel: Counter, Timer, Traces. */
public interface MetricsPort {
    void recordLlmCall(String agent, int inputTokens, int outputTokens, long durationMs);
    void recordToolExecution(String tool, long durationMs, boolean success);
    void recordError(String agent, String error);
}

/** Capability Search/RAG: Tools/Skills für Query finden. */
public interface CapabilitySearchPort {
    List<Capability> search(String query, int topK);
    void index(Capability capability);
}

/** Declarative Workflows: JSON-Definitionen laden/validieren/ausführen. */
public interface DeclarativeWorkflowPort {
    void loadDefinition(String json);
    boolean validate(String json);
    String execute(String workflowId, Map<String, Object> input);
}

/** Dynamic Workflows: Runtime-Registrierung von Workflow-Definitionen. */
public interface DynamicWorkflowPort {
    void register(String id, WorkflowDefinition def);
    void unregister(String id);
    WorkflowDefinition get(String id);
}

/** Workflow Generator: LLM-gestützte Workflow-Erstellung. */
public interface WorkflowGeneratorPort {
    String generate(String description);
}

/** Run Streaming: SSE-Events für laufende Runs. */
public interface RunStreamPort {
    void subscribe(String runId, java.util.function.Consumer<RunEvent> consumer);
    void unsubscribe(String runId, java.util.function.Consumer<RunEvent> consumer);
}

/** Settings/Config: CRUD für Konfiguration. */
public interface SettingsPort {
    Map<String, Object> get();
    void update(Map<String, Object> settings);
}

/** Capability Catalog: Tools/Skills-Katalog. */
public interface CapabilityCatalogPort {
    List<Capability> list();
    void add(Capability capability);
}

/** RBAC: Role-based Access Control. */
public interface RbacPort {
    boolean hasPermission(String user, String role, String resource);
    List<String> roles(String user);
}

/** Saga: Durable Saga mit Kompensationen. */
public interface SagaPort {
    void start(String sagaId);
    void addStep(String sagaId, String stepName, Runnable forward, Runnable compensate);
    void compensate(String sagaId);
}
```

### 9.4 Implementierungsplan (Ports → Adapter)

| Phase | Ports | Adapter | Priorität |
|---|---|---|---|
| **P0** (basis) | `GraphEngine`, `HumanDecisionPort`, `CheckpointStore` | LangGraph4j-Adapter, Temporal-Signal, SQLite-Saver | Hoch |
| **P1** (core) | `RunVisibilityPort`, `SagaPort`, `StructuredOutputPort` | Temporal-Visibility, Saga-Workflow, JSON-Schema | Hoch |
| **P2** (advanced) | `ProvenancePort`, `LongTermMemoryPort`, `McpClientPort` | QOC-Journal, In-Memory-Store, SSE-Client | Mittel |
| **P3** (observability) | `MetricsPort`, `CapabilitySearchPort`, `CapabilityCatalogPort` | Micrometer, HNSW/PgVector, Catalog | Mittel |
| **P4** (declarative) | `DeclarativeWorkflowPort`, `DynamicWorkflowPort`, `WorkflowGeneratorPort` | JSON-Parser, Registry, LLM-Generator | Niedrig |
| **P5** (streaming/config) | `RunStreamPort`, `SettingsPort`, `RbacPort` | SSE, Config-CRUD, Role-Mapping | Niedrig |

### 9.5 QOC vs. AgentFlow: was fehlt wo?

**AgentFlow hat, QOC nicht (23 Funktionen):**
Graph-Reasoning, HITL, Checkpoints, Run-Visibility, Saga, Structured Output,
Provenance, Long-term Memory, MCP-Client, Metrics, Capability Search/RAG,
Declarative Workflows, Dynamic Workflows, Workflow Generator, Run Streaming,
Settings CRUD, Capability Catalog, RBAC, Saga (durable).

**QOC hat, AgentFlow nicht (10 Funktionen):**
Session Engine, Permission-System, Crash-Recovery, Context-Compaction,
MCP-Server, A2A-Protokoll, Subagent-System, Instructions, Snapshots/Revert,
Tool-Output-Store.

**Gemeinsam:**
Subagent-Delegation, Audit/Logging, Saga/Compensation (unterschiedliche Implementierung).

## 10. Referenzen

| Thema | Pfad |
|---|---|
| QOC Engine (Facade) | `qoc/engine/.../runtime/QocEngine.java` |
| Multi-Memory-Plan | `qoc/docs/engineering/Multi-Memory.md` |
| AgentFlow-Engine-Doku | `qoc/docs/engineering/Agentflow-Engine.md` |
| Temporal-Engine-Doku | `qoc/docs/engineering/Temporal-Engine.md` |
| AgentFlow-Standalone | `/home/torsten/dev/my-projects/agentflow-standalone/` |
| AgentFlow README | `agentflow-standalone/README.md` |
| AgentFlow UI | `agentflow-standalone/UI.md` |
| AgentFlow Tests | `agentflow-standalone/Tests.md` |
| AgentFlow Ideas | `agentflow-standalone/Ideas.md` |
| AgentFlow Core | `agentflow-standalone/agentflow-core/` |
| AgentFlow App | `agentflow-standalone/agentflow-app/` |
| AgentFlow Workflows | `agentflow-standalone/agentflow-app/.../workflow/` |
| AgentFlow Controllers | `agentflow-standalone/agentflow-app/.../web/` |
| Temporal SDK | `~/.m2/repository/io/temporal/temporal-sdk/1.40.0/` |
| QOC Storage Ports | `qoc/engine/.../core/port/store/` |
| QOC Agent-Katalog | `qoc/quarkus/.../http/AgentCatalogue.java` |
| QOC MCP-Server | `qoc/quarkus/.../http/McpServerResource.java` |
| QOC A2A-Server | `qoc/quarkus/.../http/a2a/` |
| QOC Multi-Memory | `qoc/docs/engineering/Multi-Memory.md` |
| InMemory-Beschreibung | `qoc/docs/engineering/InMemory.md` |
| Layering-Entscheidung | `qoc/docs/engineering/Layering.md` |
| Core-Beschreibung | `qoc/docs/engineering/Core.md` |
| Async/Heartbeat | `qoc/docs/engineering/Async-use.md` |
| Task-Orchestrierung | `qoc/docs/engineering/Task-Orchestration.md` |
| Test-Matrix | `qoc/docs/engineering/Test.md` |

