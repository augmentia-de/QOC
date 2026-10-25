# QOC – Architecture and Runtime Diagrams

As of: 2026-10-24  
Based on: current code in `engine` and `quarkus`, plus the spec under
`docs/spec/`.

This collection answers the question: **How is QOC built, how does a
prompt flow through the system, and where is the behavior deterministic –
and where does an LLM decide?**

## Legend

| Marking | Meaning |
|---|---|
| **DETERMINISTIC** (green) | Same state + same input + same rules yield the same next state. No LLM, no free interpretation. |
| **LLM** (orange) | A language model produces text, tool calls, or a summary. The result is not deterministic. |
| **MIXED** (yellow) | Deterministic frame in which an LLM is invoked as a building block. The frame validates and decides on execution. |
| **EXTERNAL / ADAPTER** (gray) | Technical adapter or external service: HTTP, MCP, Firecrawl, file system, Git, database. |
| **STORE** (blue) | Persistence: event log, projections, snapshots. |

Important for the classification:

- QOC is **not a fully deterministic orchestrator**. The
  session frame is deterministic, but the selection of tool calls and the
  text come from the LLM.
- The LLM is **not** the instance that writes state transitions. It
  delivers proposals; the engine validates, authorizes, and executes.
- The diagrams distinguish, where needed, between **current state** and
  **target design**. A target design is explicitly marked as such.

## Diagrams

| File | Question |
|---|---|
| [`01-system-context.md`](./01-system-context.md) | Which actors, services, and stores make up the overall system? |
| [`02-architecture.md`](./02-architecture.md) | How are Maven modules, packages, and layers structured? |
| [`03-runtime-dataflow.md`](./03-runtime-dataflow.md) | What happens to a prompt from HTTP to SSE and DB? |
| [`04-session-lifecycle.md`](./04-session-lifecycle.md) | Which states does a session pass through, and how are runs coordinated? |
| [`05-class-model.md`](./05-class-model.md) | Which central classes and ports carry the system? |
| [`06-dependencies.md`](./06-dependencies.md) | Which dependencies are allowed, which forbidden? |
| [`07-tool-permission-chain.md`](./07-tool-permission-chain.md) | How is a tool call authorized and executed? |
| [`08-agent-orchestrator.md`](./08-agent-orchestrator.md) | How do agents/subagents work today – and what would a deterministic orchestrator with LLM fallback look like? |
| [`09-persistence.md`](./09-persistence.md) | Which tables, events, and transaction boundaries exist? |
| [`10-deployment.md`](./10-deployment.md) | JVM, Native, Docker, and GCP operation without Git. |

## Deterministic parts at a glance

| Area | Status | Reason |
|---|---|---|
| HTTP validation, ID parsing, error codes | deterministic | pure translation and validation |
| Session Admission / Inbox / Delivery | deterministic | fixed order, idempotency, rules |
| Event log | deterministic | append-only, gapless sequence, transaction |
| Projections | deterministic | own transactions; individual cross-store steps are not yet atomic |
| Permission Engine | deterministic | rules, wildcards, deny/ask/allow |
| Tool snapshot and tool execution | deterministic | registry, schema decode, authorizer, bounding |
| Context Builder | deterministic | fixed order, history folding, budget |
| Step frame | mixed | LLM delivers events/tool calls, engine executes |
| Provider call | LLM/external | content and tool selection come from the model |
| Compaction trigger | deterministic | overflow detection and exactly one compaction |
| Compaction content | LLM | summary is generated |
| Subagent call | mixed | frame/depth/semaphore deterministic, content LLM |
| Retention | deterministic | timestamps, configuration, deletion rules |

## Rendering

The diagrams are Mermaid code in Markdown. They render in GitHub, in the
VS Code Markdown preview (Mermaid extension), and in IntelliJ with the
Mermaid plugin. For local checks, `@mermaid-js/mermaid-cli` can be used.
