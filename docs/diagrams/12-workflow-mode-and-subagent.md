# 12 – Workflow Mode and Complete Subagent Run

## Purpose

This diagram shows two distinct execution modes in QOC:

1. **Workflow mode with `context_agent_executor`**  
   The outer loop is deterministic. Nodes are executed one after another.
   The `context_agent_executor` is a special node that runs an LLM agent in a
   child session with full or focused context and returns only the result.

2. **Complete subagent run**  
   A normal agent invokes one or more subagents in their own child sessions
   via `SubagentTool`. The subagents have their own tools and can themselves
   invoke further subagents. They receive **no** shared workflow context.

---

## A. Workflow Mode with `context_agent_executor`

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

  Client->>WE: Start workflow run
  WE->>QE: runPrompt(workflowSession, provider, ctx)
  Note over QE: MIXED<br/>workflowMode = true

  loop until StepResult.WorkflowDone
    QE->>WSP: provideStep(stepNo, ctx)
    WSP->>DND: dispatch(node, def, ctx, input)

    alt Deterministic Node
      Note over DND,WC: DETERMINISTIC<br/>decision / mcp_call / compact / guard / inject / hitl
      DND->>WC: Set output/state
      DND->>J: WorkflowNodeCompleted
    else Agent Node
      Note over DND,CS: MIXED<br/>isolated agent loop in child session
      DND->>CS: createSession(wf:agent:...)
      DND->>QE: runPrompt(child, task)
      QE->>LLM: Agent loop + tools
      LLM-->>QE: final result
      QE-->>DND: EngineResult
      DND->>WC: setNodeOutput(result only)
      DND->>J: WorkflowNodeCompleted(childSessionId)
    else Context-Agent Node
      Note over DND,CS: MIXED<br/>full/focused context, no runtime compaction
      DND->>CA: assemble(def, ctx, policy, task)
      DND->>BG: check(tokens, modelLimit)
      DND->>CS: createSession(wf:ctx:<nodeId>)
      DND->>QE: runPrompt(child, contextPrompt)
      QE->>LLM: Agent loop + tools
      LLM-->>QE: final result
      QE-->>DND: EngineResult
      DND->>WC: setNodeOutput(result only)
      DND->>J: WorkflowNodeCompleted(childSessionId)
    else Guard/Inject Node
      Note over DND,WC: DETERMINISTIC<br/>conditions, state updates, ref injection, nextNode
      DND->>WC: set state/include/nextNode
      DND->>J: WorkflowNodeCompleted
    end

    DND-->>WSP: NodeResult
    WSP-->>QE: StepResult.Finished or WorkflowDone
  end

  QE-->>WE: EngineResult
  WE-->>Client: Run result
```

### Explanation

- The outer loop uses `WorkflowStepProvider`, not the LLM step.
- Deterministic nodes only modify `WorkflowContext` and the journal.
- Agent nodes and context-agent nodes run in **child sessions**.
- Only the final result is taken over into `WorkflowContext`.
- `ContextAssembler` can deliver `FULL`, `FOCUSED`, `ARTIFACTS`, or `CUSTOM`.
- `ContextBudgetGate` aborts before the provider call if the context is too
  large.
- `Guard`/`Inject` can set `nextNode` and thereby dynamically override the
  static graph.

---

## B. Complete Subagent Run with Own Subagents and Tools

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
  CS->>LLMc: Agent loop
  LLMc-->>CS: tool call search
  CS->>T: execute(search)
  T-->>CS: Result
  LLMc-->>CS: final answer
  CS-->>SR: SubagentResult
  SR-->>ST: result
  ST-->>TR: ToolOutcome.Ok
  TR-->>PS: Tool result
  PS->>LLMp: continue with tool result
  LLMp-->>PS: tool call subagent(writer, findings)
  PS->>TR: execute(subagent)
  TR->>ST: run(parent, writer, task, depth=0)
  ST->>SR: run(parent, writer, task, depth, principal)
  SR->>NS: create nested child session
  SR->>NS: runPrompt(child, task)
  NS->>LLMn: Agent loop
  LLMn-->>NS: tool calls
  NS-->>SR: SubagentResult
  SR-->>ST: result
  ST-->>TR: ToolOutcome.Ok
  TR-->>PS: Tool result
  PS->>LLMp: continue with tool result
  LLMp-->>PS: final answer
```

### Explanation

- The parent agent decides when a subagent is invoked.
- `SubagentTool` and `SubagentRunner` create a **separate child session**.
- The subagent has its own tools and can itself invoke further subagents.
- The parent receives **only the cleaned-up final result**, not internal
  intermediate steps.
- Subagents receive **no** shared workflow context.
- The child session is retained for audit but is not carried over into
  subsequent prompts of the parent.

---

## Comparison

| Aspect | Workflow Mode with Context-Agent | Subagent Run |
|---|---|---|
| Outer loop | deterministic (WorkflowStepProvider) | LLM-driven |
| Context | WorkflowContext (structured) | Task prompt |
| LLM call | in child session of a node | in child session of the subagent |
| Tools | Node tools + agent tools | subagent tools only |
| Result return | node result only | subagent result only |
| Shared context | yes, via WorkflowContext | no |
| Cache hygiene | possible via Focused/Artifacts | within the child session |
| Audit | Journal + child session | Child session |

---

## Sources

- `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- `workflow-runner/.../runner/WorkflowStepProviderImpl.java`
- `workflow-runner/.../runner/WorkflowContext.java`
- `workflow-runner/.../runner/ContextAssembler` (target)
- `engine/.../runtime/tool/subagent/SubagentTool.java`
- `engine/.../runtime/tool/subagent/SubagentRunner.java`
- `docs/plans/WorkflowMode-ContextAgent.md`
