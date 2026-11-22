# QocEngine Loop – parallel isomorphic usage

> **Purpose:** Quick overview of how the same `QocEngine.runPrompt` loop and the same `StepResult` contract are used once as a **deterministic workflow runner** and once as an **LLM-driven ReAct loop**.

```mermaid
flowchart TB
    %% ================= Shared core =================
    subgraph CORE["QocEngine.runPrompt – shared step loop"]
        direction TB
        C1["Session + Prompt"]
        C2["while stepNo &lt;= maxSteps"]
        C3["StepResult handling<br/>Finished / Failed / Paused / WorkflowDone"]
        C1 --> C2 --> C3
        C3 -->|next step| C2
    end

    %% ================= Workflow mode =================
    subgraph WFMODE["workflowMode = true – workflow runner (deterministic)"]
        direction TB
        W1["WorkflowStepProviderImpl.provideStep"]
        W2["getNextNodeId<br/>Edges + nextNode override + circuit breaker"]
        W3["DefaultNodeDispatcher.dispatch"]
        W4{"Node type?"}
        W5["agent / context_agent_executor"]
        W6["mcp_call / decision / compact / hitl / ..."]
        W7["NodeResult → StepResult"]
        W1 --> W2 --> W3 --> W4
        W4 -->|agent node| W5
        W4 -->|deterministic| W6
        W5 --> W7
        W6 --> W7
    end

    %% ================= ReAct mode =================
    subgraph REACT["workflowMode = false – ReAct loop (LLM-driven)"]
        direction TB
        R1["Step.run<br/>LLM call"]
        R2{"Tool calls?"}
        R3["Tool execution"]
        R4["StepResult"]
        R1 --> R2
        R2 -->|yes| R3 --> R1
        R2 -->|no| R4
    end

    %% ================= Control =================
    C2 -->|workflowMode = true| W1
    C2 -->|workflowMode = false| R1
    W7 --> C3
    R4 -->|Top-Level ReAct| C3

    %% ================= Nesting =================
    W5 -->|child session| R1
    R4 -->|NodeResult| W5

    %% ================= Shared persistence =================
    DB[("Session / Journal / DB = SSOT")]
    C1 -.-> DB
    W7 -.-> DB
    R4 -.-> DB
```

## Legend

- **Solid arrows:** control flow.
- **Dashed arrows:** shared persistence (session, journal, DB as single source of truth).
- **Outer loop:** `QocEngine.runPrompt` with `workflowMode = true`; navigates deterministically across nodes.
- **Inner loop:** `QocEngine.runPrompt` with `workflowMode = false`; runs per agent node in a child session and executes LLM + tool calls.
- **Isomorphic:** Both loops deliver the same `StepResult` contract (`Finished`, `Failed`, `Paused`, `WorkflowDone`, `ToolCallOutcome`). The workflow loop decides over nodes; the ReAct loop over LLM/tool steps.
- **Two views, one state:** `WorkflowContext` (node outputs, workflow state) and the agent context (`Prepared`, history, tools) are projections of the same session/journal persistence.

## In short

The workflow runner is the **outer deterministic loop**; each agent node starts the same QocEngine loop again within it as an **inner ReAct loop**. This keeps execution, context, and persistence structurally identical – only the decision source differs: graph navigation vs. LLM/tool calls.
