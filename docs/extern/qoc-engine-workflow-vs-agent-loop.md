# QOC Engine: Workflow-Execution-Loop vs. Agent-Loop

> Ablage: `docs/qoc-engine-workflow-vs-agent-loop.md` (außerhalb des Git-Repos)
> Sprache: Deutsch

---

## 1. Überblick

QOC nutzt **denselben Kern** (`QocEngine.runPrompt`) für zwei unterschiedliche
Ausführungsmodelle:

| | **Agent-Loop** | **Workflow-Execution-Loop** |
|---|---|---|
| **Modell** | ReAct: LLM entscheidet iterativ über Tool-Calls | Deterministischer Graph: Topologie bestimmt die Reihenfolge |
| **Einstieg** | `QocEngine.runPrompt(session, agent, prompt, ...)` | `WorkflowExecutor.executeRun(def, input)` |
| **Granularität** | Ein Turn (Prompt → Antwort) | Ein Run (Graph → End-Knoten) |
| **Adaptivität** | Hoch (LLM wählt Tools, Reihenfolge, Abbruch) | Niedrig (Graph ist fest; LLM nur innerhalb von Agent-Knoten) |
| **Nachprüfbarkeit** | Journal-Events pro Step | Journal-Events pro Node + Run-Status |
| **Resume** | Session-basiert (Claim, Conversation) | Run-basiert (WorkflowRunStarted/NodeCompleted, Checkpoint) |

Beide teilen: Persistence (Session-Journal), Tools, Permissions, Provider-Adapter,
Subagents, Compaction, Revert-Snapshots.

---

## 2. Agent-Loop (ReAct)

### 2.1 Ablauf

```
runPrompt(session, agent, prompt)
  ├─ Claim erwerben (at most one execution per session)
  ├─ Preflight-Checks
  ├─ Prompt in Conversation speichern
  ├─ ContextBuilder.prepare(...)  → Prepared (System, History, Tools, ModelInfo)
  ├─ Step.run(prepared, toolContext)  ← LLM-Call
  │     ├─ LLM antwortet mit Text +/oder Tool-Calls
  │     ├─ ToolRuntime führt Tools aus (Permission-Gate, Bounding, Cache)
  │     ├─ Ergebnisse zurück ans LLM (multi-turn innerhalb des Steps)
  │     └─ Terminal-Event (TextEnded / Paused / Failed)
  ├─ Events publizieren (TextDelta, ToolCalled, ToolSuccess, …)
  ├─ Compaction (bei Bedarf)
  ├─ Journal: ExecutionStarted, …, ExecutionSucceeded/Failed
  └─ Claim freigeben → EngineResult (text, finish, tokens, structured)
```

### 2.2 Eigenschaften

- **Adaptiv**: Das LLM entscheidet, welche Tools wann aufgerufen werden,
  ob ein Tool wiederholt wird, und wann die Antwort fertig ist.
- **Multi-Turn innerhalb eines Steps**: Tool-Ergebnisse fließen zurück ins
  Modell; der Loop endet erst bei einem Terminal-Event.
- **Subagents**: Der Agent kann `subagent`/`task` aufrufen → Child-Session
  mit eigener Execution (Rekursionstiefe max. 2).
- **Parallele Tool-Calls**: Der Step kann mehrere Tools gleichzeitig
  ausführen.
- **Compaction**: Bei langen Histories wird automatisch kompaktiert.
- **Revert**: Snapshots vor dem Turn erlauben file-level Revert.
- **Idempotenz**: Tool-Result-Cache verhindert Doppel-Ausführung bei Retries.

### 2.3 Persistenz

- `SessionCreated`, `ExecutionStarted`, `ToolCalled`, `ToolSuccess`,
  `ExecutionSucceeded`/`Failed`/`Paused` im Journal (pro Session-Aggregat).
- `ConversationStore` hält die Nachrichten-History (append-only, gapless seq).
- `ClaimStore` sichert "at most one execution per session".

---

## 3. Workflow-Execution-Loop

### 3.1 Ablauf

```
WorkflowExecutor.executeRun(def, input)
  ├─ def persistieren (Store.save) + expand (SubWorkflowExpander)
  ├─ Snapshot der Definition (version, contentHash)
  ├─ Session erstellen: "workflow:<workflowId>" (runId = session.value())
  ├─ Journal: WorkflowRunStarted
  ├─ Topologische Sortierung (Kahn auf Edges)
  ├─ Für jeden Knoten (in Topologie-Reihenfolge):
  │     ├─ Journal: WorkflowNodeStarted
  │     ├─ dispatch(node, def, ctx, input)
  │     │     ├─ "agent" → runAgent()
  │     │     │     ├─ Child-Session erstellen (engine.createSession)
  │     │     │     ├─ engine.runPrompt(child, prefixedName, prompt, config, ...)
  │     │     │     └─ NodeResult(text, structured, childSessionId)
  │     │     ├─ "decision" → Branch-Evaluation
  │     │     ├─ "fork" → parallele Targets
  │     │     ├─ "join" → Merge
  │     │     ├─ "loop" → Body wiederholen (maxIterations, exitCondition)
  │     │     ├─ "subworkflow" → rekursiver Aufruf
  │     │     ├─ "hitl" → Pause (Approval)
  │     │     ├─ "mcp_call" → MCP-Tool direkt
  │     │     ├─ "llm_eval" / "llm_summarize" → QocEngine.runPrompt
  │     │     └─ "end" → Terminal
  │     ├─ Journal: WorkflowNodeCompleted (status, output, childSessionId, stateDelta)
  │     └─ bei Fehler: Journal: WorkflowNodeFailed, Run abbrechen
  ├─ Journal: WorkflowRunFinished / WorkflowRunFailed
  └─ WorkflowExecution (runId, sessionId, result, finish, failed, paused)
```

### 3.2 Eigenschaften

- **Deterministisch**: Die Topologie (Edges) bestimmt die Ausführungsreihenfolge,
  nicht das LLM. Agent-Knoten nutzen intern den adaptiven Agent-Loop, aber
  die Orchestrierung ist fest.
- **Replaybar**: Jeder Node-Start/-Abschluss ist im Journal. Bei Crash kann
  der Run ab dem letzten Checkpoint fortgesetzt werden (`resume(runId)`).
- **HITL**: `hitl`-Knoten pausieren den Run; Fortsetzung via Signal
  (`decide(boolean)`).
- **Subworkflows**: Rekursive Einbettung mit Erweiterung
  (`SubWorkflowExpander`).
- **Loops**: `loop`-Knoten mit `maxIterations` und `exitCondition`
  (ConditionEvaluator).
- **Fork/Join**: Parallele Branches und Merge.
- **Decision**: Bedingte Verzweigung (Branches mit Conditions).
- **Structured Output**: Agent-Knoten können `responseSchema` (JSON Schema)
  tragen; das Ergebnis landet in `NodeResult.structured`.

### 3.3 Persistenz

- `WorkflowRunStarted(runId, workflowId, version, contentHash, input, at)`
- `WorkflowNodeStarted(runId, nodeId, attempt, at)`
- `WorkflowNodeCompleted(runId, nodeId, status, outputText, output, childSessionId, stateDelta, at)`
- `WorkflowNodeFailed(runId, nodeId, error, attempt, at)`
- `WorkflowRunResumed(runId, attempt, at)`
- `WorkflowRunFinished(runId, status, at)` / `WorkflowRunFailed(runId, error, at)`
- Agent-Knoten erzeugen zusätzliche Sessions (Child-Sessions) mit eigenen
  Journal-Events.

---

## 4. Vergleich im Detail

### 4.1 Determinismus

| Aspekt | Agent-Loop | Workflow-Loop |
|---|---|---|
| Reihenfolge | LLM entscheidet | Graph (Edges) bestimmt |
| Tool-Auswahl | LLM wählt Tools | Knoten-Typ bestimmt (agent → LLM; mcp_call → festes Tool) |
| Abbruch | LLM beendet (TextEnded) oder Timeout | End-Knoten oder Fehler |
| Wiederholbarkeit | Nein (LLM nicht deterministisch) | Ja (Graph + Checkpoint) |
| Audit | Step-Events | Node-Events + Run-Status |

### 4.2 Wiederverwendung

Der Workflow-Loop nutzt den Agent-Loop **als Baustein**: Jeder `agent`-Knoten
ruft `engine.runPrompt(childSession, prefixedName, prompt, config, ...)` auf.
Das bedeutet:
- Dieselbe Context-Assembly, Tool-Execution, Compaction, Idempotenz.
- Dieselben Provider, Permissions, Subagents.
- Kein zweiter, ungetesteter Codepfad für LLM-Calls.

### 4.3 Zustandsverwaltung

| | Agent-Loop | Workflow-Loop |
|---|---|---|
| Primärer Zustand | Session (Conversation, Journal, Claims) | Run (WorkflowRun*-Events) + Child-Sessions |
| Node-Outputs | `ctx.setNodeOutput(nodeId, output)` (in-memory, ephemeral) | `WorkflowNodeCompleted.output` (durable im Journal) |
| Resume | Session-basiert (Claim-Recovery) | Run-basiert (Checkpoint + Replay) |
| Parallelität | Parallele Tool-Calls innerhalb eines Steps | Fork/Join-Knoten, parallele Branches |

### 4.4 Fehlerbehandlung

| | Agent-Loop | Workflow-Loop |
|---|---|---|
| Tool-Fehler | LLM sieht Fehler, kann retry/alternativ | Node-Fehler → Run abgebrochen (oder Saga-Compensation) |
| Session-Crash | Claim-Recovery, Resume | Checkpoint-Replay ab letztem Node |
| Modell-Fehler | Retry-Policy (max-attempts) | Node-Failed → Run-Failed |
| Timeout | Step-Timeout | max-resume-attempts, Temporal-Activity-Timeouts (geplant) |

### 4.5 Skalierung

| | Agent-Loop | Workflow-Loop |
|---|---|---|
| Horizontal | Sessions sind unabhängig, Claim pro Session | Runs sind unabhängig, Checkpoint pro Run |
| Parallelität | Subagents (max. Tiefe 2, Semaphore) | Fork/Join, parallele Branches |
| Ressourcen-Limits | `maxTokens`, `stepTimeoutSeconds`, Loop-Detection | `maxSteps`, `max-resume-attempts`, Token-Budget |

---

## 5. Wann welches Modell?

| Anwendungsfall | Empfehlung |
|---|---|
| Freie Exploration, Recherche, Chat | Agent-Loop |
| Deterministische Prozesse (ETL, Review-Pipeline, Genehmigungen) | Workflow-Loop |
| HITL (menschliche Entscheidung nötig) | Workflow-Loop (hitl-Knoten + Signals) |
| Langlaufende, wiederholbare Abläufe | Workflow-Loop (Checkpoint/Resume) |
| Ad-hoc Tool-Nutzung | Agent-Loop |
| Multi-Step mit expliziten Abhängigkeiten | Workflow-Loop |
| Subagent-Delegation innerhalb eines Steps | Agent-Loop (subagent-Tool) |

---

## 6. Architektur-Diagramm

```
┌─────────────────────────────────────────────────────────┐
│                    Workflow-Loop                        │
│  WorkflowExecutor.executeRun(def, input)                 │
│    ├─ TopologicalSort.sort(def)                         │
│    ├─ for nodeId in order:                              │
│    │     dispatch(node) ──► Agent-Knoten                │
│    │                       └─ QocEngine.runPrompt()     │
│    │                          └─ Agent-Loop (ReAct)     │
│    │     Decision/Fork/Join/Loop/MCP/HITL               │
│    └─ Journal: WorkflowRunStarted/Node*/Finished         │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│                    Agent-Loop                           │
│  QocEngine.runPrompt(session, agent, prompt)            │
│    ├─ Claim acquire                                     │
│    ├─ ContextBuilder.prepare()                          │
│    ├─ Step.run() ──► LLM + ToolRuntime                  │
│    │   └─ (multi-turn: Text → Tool → Text → ...)       │
│    ├─ Compaction / Revert                               │
│    └─ Journal: ExecutionStarted/Tool*/Succeeded          │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│              Shared Infrastructure                      │
│  SessionStore, ConversationStore, Journal, ClaimStore,   │
│  ToolOutputStore, ProviderAdapter, PermissionGate,      │
│  McpClient, SubagentRunner, CompactionService           │
└─────────────────────────────────────────────────────────┘
```

---

## 7. Implementierungs-Referenzen

| Komponente | Datei |
|---|---|
| Agent-Loop | `engine/runtime/QocEngine.java` (`runPrompt`) |
| Workflow-Loop | `workflow-runner/runner/WorkflowExecutor.java` (`executeRun`, `run`) |
| Node-Dispatch | `workflow-runner/runner/DefaultNodeDispatcher.java` (`dispatch`, `runAgent`) |
| Topologie | `workflow-runner/runner/TopologicalSort.java` |
| Journal-Events | `engine/core/model/SessionEvent.java` (`WorkflowRun*`, `WorkflowNode*`) |
| Event-Sink | `workflow-runner/runner/JournalWorkflowEventSink.java` |
| Agent-Config-Konvertierung | `DefaultNodeDispatcher.toAgentConfig()` |
| Structured Output | `workflow-runner/runner/StructuredOutputConverter.java` |
| Resume | `WorkflowExecutor.resume(runId)` |
| Checkpoint | `workflow-runner/store/CheckpointStore.java` |

---

## 8. Fazit

QOC verwendet **einen** Engine-Kern für beide Modelle. Der Agent-Loop ist
adaptiv (LLM-gesteuert), der Workflow-Loop ist deterministisch (graph-gesteuert).
Der Workflow-Loop nutzt den Agent-Loop als Baustein für Agent-Knoten und
erbt damit dessen Erprobtheit (Tools, Permissions, Compaction, Idempotenz).
Der Unterschied liegt in der **Orchestrierung**, nicht in der Ausführung.
