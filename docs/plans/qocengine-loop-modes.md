# QocEngine Loop – parallele isomorphe Nutzung

> **Zweck:** Schnelle Übersicht, wie derselbe `QocEngine.runPrompt`-Loop und derselbe `StepResult`-Vertrag einmal als **deterministischer Workflow-Runner** und einmal als **LLM-getriebener ReAct-Loop** verwendet werden.

```mermaid
flowchart TB
    %% ================= Gemeinsamer Kern =================
    subgraph CORE["QocEngine.runPrompt – gemeinsamer Step-Loop"]
        direction TB
        C1["Session + Prompt"]
        C2["while stepNo &lt;= maxSteps"]
        C3["StepResult-Handling<br/>Finished / Failed / Paused / WorkflowDone"]
        C1 --> C2 --> C3
        C3 -->|nächster Schritt| C2
    end

    %% ================= Workflow-Modus =================
    subgraph WFMODE["workflowMode = true – Workflow-Runner (deterministisch)"]
        direction TB
        W1["WorkflowStepProviderImpl.provideStep"]
        W2["getNextNodeId<br/>Edges + nextNode-Override + Circuit-Breaker"]
        W3["DefaultNodeDispatcher.dispatch"]
        W4{"Node-Typ?"}
        W5["agent / context_agent_executor"]
        W6["mcp_call / decision / compact / hitl / ..."]
        W7["NodeResult → StepResult"]
        W1 --> W2 --> W3 --> W4
        W4 -->|Agent-Node| W5
        W4 -->|deterministisch| W6
        W5 --> W7
        W6 --> W7
    end

    %% ================= ReAct-Modus =================
    subgraph REACT["workflowMode = false – ReAct-Loop (LLM-getrieben)"]
        direction TB
        R1["Step.run<br/>LLM-Call"]
        R2{"Tool Calls?"}
        R3["Tool-Ausführung"]
        R4["StepResult"]
        R1 --> R2
        R2 -->|ja| R3 --> R1
        R2 -->|nein| R4
    end

    %% ================= Steuerung =================
    C2 -->|workflowMode = true| W1
    C2 -->|workflowMode = false| R1
    W7 --> C3
    R4 -->|Top-Level ReAct| C3

    %% ================= Verschachtelung =================
    W5 -->|Kind-Session| R1
    R4 -->|NodeResult| W5

    %% ================= Gemeinsame Persistenz =================
    DB[("Session / Journal / DB = SSOT")]
    C1 -.-> DB
    W7 -.-> DB
    R4 -.-> DB
```

## Legende

- **Durchgezogene Pfeile:** Steuerfluss.
- **Gestrichelte Pfeile:** gemeinsame Persistenz (Session, Journal, DB als Single Source of Truth).
- **Äußerer Loop:** `QocEngine.runPrompt` mit `workflowMode = true`; navigiert deterministisch über Nodes.
- **Innerer Loop:** `QocEngine.runPrompt` mit `workflowMode = false`; läuft pro Agent-Node in einer Kind-Session und führt LLM + Tool-Calls aus.
- **Isomorph:** Beide Loops liefern denselben `StepResult`-Vertrag (`Finished`, `Failed`, `Paused`, `WorkflowDone`, `ToolCallOutcome`). Der Workflow-Loop entscheidet über Nodes, der ReAct-Loop über LLM-/Tool-Schritte.
- **Zwei Sichten, ein Zustand:** `WorkflowContext` (Node-Outputs, Workflow-State) und der Agent-Kontext (`Prepared`, History, Tools) sind Projektionen derselben Session-/Journal-Persistenz.

## Kurz gesagt

Der Workflow-Runner ist der **äußere deterministische Loop**; jeder Agent-Node startet darin denselben QocEngine-Loop erneut als **inneren ReAct-Loop**. Dadurch bleiben Ausführung, Kontext und Persistenz strukturell gleich – nur die Entscheidungsquelle unterscheidet sich: Graph-Navigation vs. LLM/Tool-Calls.