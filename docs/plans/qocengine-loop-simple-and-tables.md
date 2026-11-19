# QocEngine Loop – Parallel Isomorphic Usage

> **Purpose:** The same `QocEngine.runPrompt` loop and the same `StepResult` contract – once as a **deterministic workflow run**, once as an **LLM-driven agent run**.

![QocEngine Loop – workflow run at top, shared tables in the middle, agent run at bottom](qocengine-loop.svg)

> **Note:** The SVG is the authoritative representation. The earlier Mermaid draft has been removed because Mermaid does not support fixed positions. The workflow run runs at the top; the agent run is started from the agent node (`W4`) and returns its result to `W5` via `childSessionId`.

## Can you specify positions in Mermaid?

**No** – Mermaid does not support absolute coordinates for nodes. Ordering can only be influenced indirectly:

- `direction TB` / `LR` per graph and subgraph
- order of declaration
- grouping into subgraphs
- invisible edges (`~~~`) for forced ordering
- `linkStyle` only for lines, not for positions

For exact positions you need a different tool:

- **Graphviz DOT** with `neato -n` and `pos` attributes
- **draw.io**, **Excalidraw** or **tldraw** (manual layout)
- **Mermaid + ELK** (`%%{init: {"layout": "elk"}}%%`) for better automation, but still without fixed coordinates

## The identical logic

| Phase | Workflow-Run | Agent-Run |
|---|---|---|
| **WHILE** | `stepNo <= maxSteps` | `stepNo <= maxSteps` |
| **STEP** | determine next node | LLM call |
| **TOOL** | execute node (agent, mcp_call, decision, …) | execute tool |
| **RESULT** | `StepResult` | `StepResult` |
| **Self-link** | `RESULT → WHILE` | `RESULT → WHILE` |
| **End** | `WorkflowDone` | `Finished` |

## What is the "child session"?

The child session is the session of the agent node. It is created with `engine.createSession(...)` and contains:

- its own `WHILE` loop,
- its own `session_message` history (user/assistant/tool messages),
- its own `event` entries (`Step*`, `Tool*`, `Execution*`),
- its own result, which is returned to the workflow node as `NodeResult`.

The link is recorded in the workflow journal:

| From | To | Field |
|---|---|---|
| Workflow-Node | Agent-Run | `WorkflowNodeCompleted.childSessionId` |
| Workflow-Run | Workflow-Session | `session.id = runId` |
| Agent-Run | Agent-Session | `session.id = childSessionId` |

## Important tables (dual-use)

> **Legend:** "yes" = used directly in this loop; "indirect" = via the nested agent/tool run; "no" = not in this loop.

| Table | Content | Workflow-Run | Agent-Run | Note |
|---|---|---|---|---|
| `session` | Session header | **yes** | **yes** | `runId` vs. `childSessionId` |
| `session_message` | Conversation (`id`, `session_id`, `seq`, `type`, `data`, `parent_id`) | **yes** | **yes** | Workflow: prompt + node output as `AssistantMessage`; Agent: complete turns including tool cards. `SystemMessage` is a `type` value, not a separate table. |
| `event` | Event journal (`aggregate_id`, `seq`, `type`, `data`) | **yes** | **yes** | Workflow: `WorkflowRun*` / `WorkflowNode*`; Agent-Run: `Step*` / `Tool*` / `Execution*` |
| `event_sequence` | Sequence counter per `aggregate_id` | **yes** | **yes** | |
| `session_claim` | Active execution claim | **yes** | **yes** | prevents parallel runs |
| `resume_counter` | Resume attempts | **yes** | **yes** | |
| `session_inbox` | Steering/queue messages | **yes** | **yes** | |
| `pending_approval` | HITL approvals | indirect | **yes** | Workflow HITL pauses; agent tools create approvals |
| `question_request` | Questions to humans | indirect | **yes** | |
| `saved_approval` | Saved approval rules | indirect | **yes** | project-scoped approval rules |
| `tool_output` | Tool artifacts | no | **yes** | `mcp_call` uses MCP directly, not the `ToolOutputStore` |
| `tool_result_cache` | Idempotency cache | no | **yes** | |
| `instruction_state` | Instruction state | no | **yes** | `workflowMode` does not invoke `ContextBuilder` per step |
| `instruction_entry` | Instruction values | no | **yes** | |
| `instruction_blob` | Instruction blobs | no | **yes** | |
| `snapshot` | File snapshots | no | **yes** | tool runs / revert |
| `snapshot_file` | Snapshot files | no | **yes** | |
| `workflow_definition` | Workflow spec (SSOT) | **yes** | no | |
| `workflow_version` | Workflow versions | **yes** | no | |
| `agent_definition` | Agent spec (DB) | **yes** | **yes** | Workflow node references, agent run executes |
| `mcp_server` | MCP server config | **yes** | **yes** | `mcp_call` and MCP tools |
| `credential` | MCP/integration credentials | **yes** | **yes** | OAuth/secrets for MCP |

> **Note:** There is no separate table `system_messages`. System messages live in `session_message` with `type = 'SystemMessage'`. The combined table nodes in the middle band group tables of the same role; all mentioned tables are included.

## Key takeaway

There is **no separate workflow-run table**. The workflow state is replayed from `event` (`WorkflowRunJournal`). Workflow run and agent run are both normal sessions; they are distinguished only by `session_id` / `aggregate_id`. As a result, `session_message`, `event`, and the session tables are genuine dual-use tables.