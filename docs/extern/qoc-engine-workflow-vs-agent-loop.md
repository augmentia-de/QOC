# QOC Engine: Workflow-Execution-Loop vs. Agent-Loop

> Location: `docs/qoc-engine-workflow-vs-agent-loop.md` (outside the Git repo)
> Language: English

---

## 1. Overview

QOC uses **one shared core** (`QocEngine.runPrompt`) for two different
execution models:

| | **Agent-Loop** | **Workflow-Execution-Loop** |
|---|---|---|
| **Model** | ReAct: LLM decides iteratively on tool calls | Deterministic graph: topology determines the order |
| **Entry point** | `QocEngine.runPrompt(session, agent, prompt, ...)` | `WorkflowExecutor.executeRun(def, input)` |
| **Granularity** | One turn (prompt → response) | One run (graph → end node) |
| **Adaptivity** | High (LLM chooses tools, order, termination) | Low (graph is fixed; LLM only inside agent nodes) |
| **Auditability** | Journal events per step | Journal events per node + run status |
| **Resume** | Session-based (claim, conversation) | Run-based (WorkflowRunStarted/NodeCompleted, checkpoint) |

Shared by both: Persistence (session journal), tools, permissions, provider
adapters, subagents, compaction, revert snapshots.

---

## 2. Agent-Loop (ReAct)

### 2.1 Flow

```
runPrompt(session, agent, prompt)
  ├─ Acquire claim (at most one execution per session)
  ├─ Preflight checks
  ├─ Store prompt in conversation
  ├─ ContextBuilder.prepare(...)  → Prepared (System, History, Tools, ModelInfo)
  ├─ Step.run(prepared, toolContext)  ← LLM call
  │     ├─ LLM responds with text and/or tool calls
  │     ├─ ToolRuntime executes tools (permission gate, bounding, cache)
  │     ├─ Results fed back to the LLM (multi-turn within the step)
  │     └─ Terminal event (TextEnded / Paused / Failed)
  ├─ Publish events (TextDelta, ToolCalled, ToolSuccess, …)
  ├─ Compaction (if needed)
  ├─ Journal: ExecutionStarted, …, ExecutionSucceeded/Failed
  └─ Release claim → EngineResult (text, finish, tokens, structured)
```

### 2.2 Characteristics

- **Adaptive**: The LLM decides which tools are called when, whether a tool
  is repeated, and when the response is complete.
- **Multi-turn within a step**: Tool results flow back into the model; the
  loop only ends on a terminal event.
- **Subagents**: The agent can call `subagent`/`task` → child session with
  its own execution (max recursion depth 2).
- **Parallel tool calls**: The step can execute multiple tools at once.
- **Compaction**: Long histories are compacted automatically.
- **Revert**: Snapshots before the turn allow file-level revert.
- **Idempotency**: The tool result cache prevents duplicate execution on
  retries.

### 2.3 Persistence

- `SessionCreated`, `ExecutionStarted`, `ToolCalled`, `ToolSuccess`,
  `ExecutionSucceeded`/`Failed`/`Paused` in the journal (per session
  aggregate).
- `ConversationStore` holds the message history (append-only, gapless seq).
- `ClaimStore` ensures "at most one execution per session".

---

## 3. Workflow-Execution-Loop

### 3.1 Flow

```
WorkflowExecutor.executeRun(def, input)
  ├─ Persist definition (Store.save) + expand (SubWorkflowExpander)
  ├─ Snapshot of the definition (version, contentHash)
  ├─ Create session: "workflow:<workflowId>" (runId = session.value())
  ├─ Journal: WorkflowRunStarted
  ├─ Topological sort (Kahn on edges)
  ├─ For each node (in topological order):
  │     ├─ Journal: WorkflowNodeStarted
  │     ├─ dispatch(node, def, ctx, input)
  │     │     ├─ "agent" → runAgent()
  │     │     │     ├─ Create child session (engine.createSession)
  │     │     │     ├─ engine.runPrompt(child, prefixedName, prompt, config, ...)
  │     │     │     └─ NodeResult(text, structured, childSessionId)
  │     │     ├─ "decision" → branch evaluation
  │     │     ├─ "fork" → parallel targets
  │     │     ├─ "join" → merge
  │     │     ├─ "loop" → repeat body (maxIterations, exitCondition)
  │     │     ├─ "subworkflow" → recursive call
  │     │     ├─ "hitl" → pause (approval)
  │     │     ├─ "mcp_call" → MCP tool directly
  │     │     ├─ "llm_eval" / "llm_summarize" → QocEngine.runPrompt
  │     │     └─ "end" → terminal
  │     ├─ Journal: WorkflowNodeCompleted (status, output, childSessionId, stateDelta)
  │     └─ on error: Journal: WorkflowNodeFailed, abort run
  ├─ Journal: WorkflowRunFinished / WorkflowRunFailed
  └─ WorkflowExecution (runId, sessionId, result, finish, failed, paused)
```

### 3.2 Characteristics

- **Deterministic**: The topology (edges) determines the execution order,
  not the LLM. Agent nodes use the adaptive agent loop internally, but the
  orchestration is fixed.
- **Replayable**: Every node start/completion is in the journal. On crash,
  the run can resume from the last checkpoint (`resume(runId)`).
- **HITL**: `hitl` nodes pause the run; continuation via signal
  (`decide(boolean)`).
- **Subworkflows**: Recursive embedding with expansion
  (`SubWorkflowExpander`).
- **Loops**: `loop` nodes with `maxIterations` and `exitCondition`
  (ConditionEvaluator).
- **Fork/Join**: Parallel branches and merge.
- **Decision**: Conditional branching (branches with conditions).
- **Structured output**: Agent nodes can carry a `responseSchema` (JSON
  Schema); the result lands in `NodeResult.structured`.

### 3.3 Persistence

- `WorkflowRunStarted(runId, workflowId, version, contentHash, input, at)`
- `WorkflowNodeStarted(runId, nodeId, attempt, at)`
- `WorkflowNodeCompleted(runId, nodeId, status, outputText, output, childSessionId, stateDelta, at)`
- `WorkflowNodeFailed(runId, nodeId, error, attempt, at)`
- `WorkflowRunResumed(runId, attempt, at)`
- `WorkflowRunFinished(runId, status, at)` / `WorkflowRunFailed(runId, error, at)`
- Agent nodes produce additional sessions (child sessions) with their own
  journal events.

---

## 4. Detailed Comparison

### 4.1 Determinism

| Aspect | Agent-Loop | Workflow-Loop |
|---|---|---|
| Order | LLM decides | Graph (edges) determines |
| Tool selection | LLM chooses tools | Node type determines (agent → LLM; mcp_call → fixed tool) |
| Termination | LLM finishes (TextEnded) or timeout | End node or error |
| Reproducibility | No (LLM is non-deterministic) | Yes (graph + checkpoint) |
| Audit | Step events | Node events + run status |

### 4.2 Reuse

The workflow loop uses the agent loop **as a building block**: each `agent`
node calls `engine.runPrompt(childSession, prefixedName, prompt, config, ...)`.
This means:
- Same context assembly, tool execution, compaction, idempotency.
- Same providers, permissions, subagents.
- No second, untested code path for LLM calls.

### 4.3 State Management

| | Agent-Loop | Workflow-Loop |
|---|---|---|
| Primary state | Session (conversation, journal, claims) | Run (WorkflowRun* events) + child sessions |
| Node outputs | `ctx.setNodeOutput(nodeId, output)` (in-memory, ephemeral) | `WorkflowNodeCompleted.output` (durable in journal) |
| Resume | Session-based (claim recovery) | Run-based (checkpoint + replay) |
| Concurrency | Parallel tool calls within a step | Fork/join nodes, parallel branches |

### 4.4 Error Handling

| | Agent-Loop | Workflow-Loop |
|---|---|---|
| Tool errors | LLM sees the error, can retry/alternatives | Node error → run aborted (or saga compensation) |
| Session crash | Claim recovery, resume | Checkpoint replay from last node |
| Model errors | Retry policy (max attempts) | Node failed → run failed |
| Timeout | Step timeout | max-resume-attempts, Temporal activity timeouts (planned) |

### 4.5 Scaling

| | Agent-Loop | Workflow-Loop |
|---|---|---|
| Horizontal | Sessions are independent, claim per session | Runs are independent, checkpoint per run |
| Concurrency | Subagents (max depth 2, semaphore) | Fork/join, parallel branches |
| Resource limits | `maxTokens`, `stepTimeoutSeconds`, loop detection | `maxSteps`, `max-resume-attempts`, token budget |

---

## 5. When to Use Which Model?

| Use case | Recommendation |
|---|---|
| Free exploration, research, chat | Agent-Loop |
| Deterministic processes (ETL, review pipeline, approvals) | Workflow-Loop |
| HITL (human decision required) | Workflow-Loop (hitl nodes + signals) |
| Long-running, repeatable flows | Workflow-Loop (checkpoint/resume) |
| Ad-hoc tool usage | Agent-Loop |
| Multi-step with explicit dependencies | Workflow-Loop |
| Subagent delegation within a step | Agent-Loop (subagent tool) |

---

## 6. Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    Workflow-Loop                        │
│  WorkflowExecutor.executeRun(def, input)                 │
│    ├─ TopologicalSort.sort(def)                         │
│    ├─ for nodeId in order:                              │
│    │     dispatch(node) ──► Agent node                 │
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

## 7. Implementation References

| Component | File |
|---|---|
| Agent-Loop | `engine/runtime/QocEngine.java` (`runPrompt`) |
| Workflow-Loop | `workflow-runner/runner/WorkflowExecutor.java` (`executeRun`, `run`) |
| Node dispatch | `workflow-runner/runner/DefaultNodeDispatcher.java` (`dispatch`, `runAgent`) |
| Topology | `workflow-runner/runner/TopologicalSort.java` |
| Journal events | `engine/core/model/SessionEvent.java` (`WorkflowRun*`, `WorkflowNode*`) |
| Event sink | `workflow-runner/runner/JournalWorkflowEventSink.java` |
| Agent config conversion | `DefaultNodeDispatcher.toAgentConfig()` |
| Structured output | `workflow-runner/runner/StructuredOutputConverter.java` |
| Resume | `WorkflowExecutor.resume(runId)` |
| Checkpoint | `workflow-runner/store/CheckpointStore.java` |

---

## 8. Conclusion

QOC uses **a single** engine core for both models. The agent loop is
adaptive (LLM-driven), the workflow loop is deterministic (graph-driven).
The workflow loop uses the agent loop as a building block for agent nodes
and thereby inherits its maturity (tools, permissions, compaction,
idempotency). The difference lies in the **orchestration**, not in the
execution.
