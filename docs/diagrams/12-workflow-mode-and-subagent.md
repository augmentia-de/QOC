# 12 – Workflow-Modus und vollständiger Subagent-Lauf

## Zweck

Dieses Diagramm zeigt zwei getrennte Ausführungsarten in QOC:

1. **Workflow-Modus mit `context_agent_executor`**  
   Der äußere Loop ist deterministisch. Nodes werden nacheinander ausgeführt.
   Der `context_agent_executor` ist ein spezieller Node, der in einer
   Kind-Session einen LLM-Agenten mit vollem oder fokussiertem Kontext
   ausführt und nur das Ergebnis zurückgibt.

2. **Vollständiger Subagent-Lauf**  
   Ein normaler Agent ruft über `SubagentTool` einen oder mehrere Subagenten
   in eigenen Kind-Sessions auf. Die Subagenten haben eigene Tools und können
   selbst weitere Subagenten aufrufen. Sie erhalten **keinen** Shared-
   Workflow-Kontext.

---

## A. Workflow-Modus mit `context_agent_executor`

```mermaid
sequenceDiagram
  autonumber
  actor Client as Client
  participant WE as WorkflowExecutor
  participant QE as QocEngine
  participant WSP as WorkflowStepProvider
  participant DND as DefaultNodeDispatcher
  participant WC as WorkflowContext
  participant CA as ContextAssembler
  participant BG as ContextBudgetGate
  participant CS as ChildSession
  participant LLM as LLM
  participant TR as ToolRuntime
  participant J as Journal

  Client->>WE: Workflow-Run starten
  WE->>QE: runPrompt(workflowSession, provider, ctx)
  Note over QE: MIXED<br/>workflowMode = true

  loop bis StepResult.WorkflowDone
    QE->>WSP: provideStep(stepNo, ctx)
    WSP->>DND: dispatch(node, def, ctx, input)

    alt Deterministischer Node
      Note over DND,WC: DETERMINISTIC<br/>decision / mcp_call / compact / guard / inject / hitl
      DND->>WC: Output/State setzen
      DND->>J: WorkflowNodeCompleted
    else Agent-Node
      Note over DND,CS: MIXED<br/>isolierter Agent-Loop in Kind-Session
      DND->>CS: createSession(wf:agent:...)
      DND->>QE: runPrompt(child, task)
      QE->>LLM: Agent-Loop + Tools
      LLM-->>QE: final result
      QE-->>DND: EngineResult
      DND->>WC: setNodeOutput(result only)
      DND->>J: WorkflowNodeCompleted(childSessionId)
    else Context-Agent-Node
      Note over DND,CS: MIXED<br/>voller/fokussierter Kontext, keine Runtime-Compaction
      DND->>CA: assemble(def, ctx, policy, task)
      DND->>BG: check(tokens, modelLimit)
      DND->>CS: createSession(wf:ctx:<nodeId>)
      DND->>QE: runPrompt(child, contextPrompt)
      QE->>LLM: Agent-Loop + Tools
      LLM-->>QE: final result
      QE-->>DND: EngineResult
      DND->>WC: setNodeOutput(result only)
      DND->>J: WorkflowNodeCompleted(childSessionId)
    else Guard-/Inject-Node
      Note over DND,WC: DETERMINISTIC<br/>Bedingungen, State-Updates, Ref-Injektion, nextNode
      DND->>WC: state/include/nextNode setzen
      DND->>J: WorkflowNodeCompleted
    end

    DND-->>WSP: NodeResult
    WSP-->>QE: StepResult.Finished oder WorkflowDone
  end

  QE-->>WE: EngineResult
  WE-->>Client: Run-Ergebnis
```

### Erläuterung

- Der äußere Loop nutzt `WorkflowStepProvider`, nicht den LLM-Step.
- Deterministische Nodes verändern nur `WorkflowContext` und Journal.
- Agent-Nodes und Context-Agent-Nodes laufen in **Kind-Sessions**.
- Nur das finale Ergebnis wird in `WorkflowContext` übernommen.
- `ContextAssembler` kann `FULL`, `FOCUSED`, `ARTIFACTS` oder `CUSTOM`
  liefern.
- `ContextBudgetGate` bricht vor dem Provider-Call ab, wenn der Kontext zu
  groß ist.
- `Guard`/`Inject` können `nextNode` setzen und damit den statischen Graph
  dynamisch überschreiben.

---

## B. Vollständiger Subagent-Lauf mit eigenen Subagenten und Tools

```mermaid
sequenceDiagram
  autonumber
  participant PS as Parent-Session
  participant LLMp as Parent-LLM
  participant TR as ToolRuntime
  participant ST as SubagentTool
  participant SR as SubagentRunner
  participant CS as Child-Session
  participant LLMc as Child-LLM
  participant T as Tools
  participant NS as Nested-Child-Session
  participant LLMn as Nested-LLM

  PS->>LLMp: Prompt
  LLMp-->>PS: tool call subagent(researcher, task)
  PS->>TR: execute(subagent)
  TR->>ST: run(parent, researcher, task, depth=0)
  ST->>SR: run(parent, researcher, task, depth, principal)
  SR->>CS: create child session (parent affinity)
  SR->>CS: runPrompt(child, task)
  CS->>LLMc: Agent-Loop
  LLMc-->>CS: tool call search
  CS->>T: execute(search)
  T-->>CS: Ergebnis
  LLMc-->>CS: final answer
  CS-->>SR: SubagentResult
  SR-->>ST: result
  ST-->>TR: ToolOutcome.Ok
  TR-->>PS: Tool-Ergebnis
  PS->>LLMp: weiter mit Tool-Ergebnis
  LLMp-->>PS: tool call subagent(writer, findings)
  PS->>TR: execute(subagent)
  TR->>ST: run(parent, writer, task, depth=0)
  ST->>SR: run(parent, writer, task, depth, principal)
  SR->>NS: create nested child session
  SR->>NS: runPrompt(child, task)
  NS->>LLMn: Agent-Loop
  LLMn-->>NS: tool calls
  NS-->>SR: SubagentResult
  SR-->>ST: result
  ST-->>TR: ToolOutcome.Ok
  TR-->>PS: Tool-Ergebnis
  PS->>LLMp: weiter mit Tool-Ergebnis
  LLMp-->>PS: finale Antwort
```

### Erläuterung

- Der Parent-Agent entscheidet, wann ein Subagent aufgerufen wird.
- `SubagentTool` und `SubagentRunner` erzeugen eine **eigene Kind-Session**.
- Der Subagent hat eigene Tools und kann selbst weitere Subagenten
  aufrufen.
- Der Parent erhält **nur das bereinigte Endergebnis**, keine internen
  Zwischenschritte.
- Subagenten erhalten **keinen** Shared-Workflow-Kontext.
- Die Kind-Session bleibt für Audit erhalten, wird aber nicht in
  nachfolgende Prompts des Parent übernommen.

---

## Gegenüberstellung

| Aspekt | Workflow-Modus mit Context-Agent | Subagent-Lauf |
|---|---|---|
| Äußerer Loop | deterministisch (WorkflowStepProvider) | LLM-gesteuert |
| Kontext | WorkflowContext (strukturiert) | Task-Prompt |
| LLM-Aufruf | in Kind-Session eines Nodes | in Kind-Session des Subagenten |
| Tools | Node-Tools + Agent-Tools | nur Subagent-Tools |
| Ergebnis-Rückgabe | nur Node-Ergebnis | nur Subagent-Ergebnis |
| Shared-Kontext | ja, über WorkflowContext | nein |
| Cache-Hygiene | durch Focused/Artifacts möglich | innerhalb der Kind-Session |
| Audit | Journal + Kind-Session | Kind-Session |

---

## Quellen

- `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- `workflow-runner/.../runner/WorkflowStepProviderImpl.java`
- `workflow-runner/.../runner/WorkflowContext.java`
- `workflow-runner/.../runner/ContextAssembler` (Ziel)
- `engine/.../runtime/tool/subagent/SubagentTool.java`
- `engine/.../runtime/tool/subagent/SubagentRunner.java`
- `docs/plans/WorkflowMode-ContextAgent.md`
