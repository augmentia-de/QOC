# QOC – Optimization (consolidated)

> Konsolidierte Dokumentation der Optimierungshebel in QOC.
> Entstanden aus `optimization.md` und `optimization.md`.
> Status: 2026-11-09

---

# Teil 1 – Optimization Opportunities

# QOC – Optimization Opportunities

As of: 2026-10-24  
Reference: current code in `engine` and `quarkus`, spec under
`docs/spec/`.

This document collects **all sensible optimization levers** for QOC. It is a
working basis, not a decision. Not every lever fits every operating model; the
prioritization depends on whether token cost, latency, memory usage, stability,
or maintainability is the most important goal.

## Contents

- [1. What "more efficient" can mean](#1-what-more-efficient-can-mean)
- [2. Fastest levers (P0)](#2-fastest-levers-p0)
- [3. Prompt and context optimization](#3-prompt-and-context-optimization)
- [4. Tool and skill optimization](#4-tool-and-skill-optimization)
- [5. Model and provider optimization](#5-model-and-provider-optimization)
- [6. Subagent and context handoff optimization](#6-subagent-and-context-handoff-optimization)
- [7. Deterministic orchestrator](#7-deterministic-orchestrator)
- [8. Execution and concurrency](#8-execution-and-concurrency)
- [9. Persistence and database](#9-persistence-and-database)
- [10. Runtime and deployment](#10-runtime-and-deployment)
- [11. Observability](#11-observability)
- [12. Sensible starting values](#12-sensible-starting-values)
- [13. Roadmap](#13-roadmap)
- [14. Checklist](#14-checklist)
- [15. Anti-patterns](#15-anti-patterns)
- [16. Open questions](#16-open-questions)
- [17. References](#17-references)

## 1. What "more efficient" can mean

| Goal | Typical metric |
|---|---|
| Lower LLM costs | Input/output tokens per prompt, cache hit rate |
| Lower latency | p50/p95 per prompt, time to first token, tool duration |
| Fewer model calls | Steps per prompt, subagent calls, retries |
| Less context | Context tokens before compaction, history size |
| Less CPU/RAM | RSS, CPU time, GC pauses, native image size |
| Less persistence growth | DB size, event count, tool output size |
| Higher stability | Error rate, retry rate, aborted sessions |
| Better maintainability | Number of tools/skills, prompt duplicates, config complexity |

**Important:** Without a baseline measurement, every optimization is only a
guess. Measure first, then change.

## 2. Fastest levers (P0)

1. **Slim down agent profiles**
   - Fewer tools per agent.
   - Lower `maxSteps`.
   - Appropriate model per agent.
   - No "standard tools for everyone".

2. **Limit the skill catalog**
   - Use `allow`, `deny`, and `max` consistently.
   - Render only visible/relevant skills.
   - Keep skill descriptions short.

3. **Bound tool outputs**
   - Use `OutputBounding`.
   - Store complete outputs; give the model only excerpts.
   - No huge file lists or shell output in the context.

4. **Enable retention**
   - Delete old sessions, tool outputs, and logs.
   - Check the 14-day default; use shorter for short-lived tasks.

5. **Disable unused MCP servers**
   - Do not start servers that are not needed.
   - Do not include MCP tools in the global snapshot that no agent needs.

6. **Remove prompt duplicates**
   - One canonical system prompt per agent.
   - Do not copy skill guidance into the agent prompt additionally.
   - Do not rebuild the base prompt manually.

7. **Reduce logging/tracing**
   - No complete prompts/responses in normal operation.
   - LLM trace only when needed or with rotation.

## 3. Prompt and context optimization

### 3.1 System prompt

- **One canonical prompt per agent.** No mixing of base prompt, agent prompt,
  and skill guidance with overlapping rules.
- **Separate the common prefix and the agent-specific part.**
  - common rules first,
  - agent role after that,
  - task-specific instructions last.
- **Use only one fallback:** `agent.system()` **or** `basePrompt`, not both
  with the same content.
- **Keep the tool list short.** Fill `${tools}` only with the actually visible
  tools.
- **Append `sys-guide` only when the tools are present.** This already
  happens; check conditions and keep the text concise.
- **Do not duplicate plan-mode rules** in the system prompt and as a reminder.
- **Insert MCP instructions only once** and only when servers are actually
  connected.

### 3.2 Skill instructions

- **Keep the skill list small.** Every listed skill costs tokens.
- **Use `allow`/`deny`/`max`** to limit the catalog per agent.
- **Frontmatter instead of full text** in the system context; load the skill
  content on demand via the `skill` tool.
- **Skill delta instead of full reload** when the catalog changes.
- **No duplicate skill descriptions** in the agent prompt and the catalog.
- **Render skill guidance only once**; `SkillCatalog.instructions()` already
  returns it as a block.

### 3.3 History and compaction

- **Use history folding** and checkpoints.
- **Trigger compaction earlier**, not only on overflow.
- **Choose `keepMessages` deliberately** (starting value 8, measure per
  model).
- **Summary update instead of full summary** when a compaction already exists.
- **Do not copy raw history into subagents**; pass a summary or relevant
  excerpts instead.
- **Do not include large tool results** in the history; store them as a
  reference/ID instead.
- **Consider old system/synthetic messages** during compaction.

### 3.4 Prompt cache

- **Produce a stable prefix:** same system prompt, same tool order, same static
  blocks.
- **Place dynamic parts at the end:** task, file excerpts, tool results.
- **Set cache affinity deliberately.** A cache key that changes per session
  prevents shared cache use.
- **Use the provider cache only if the provider supports it**; otherwise it is
  only an additional header with no effect.
- **Measure the cache hit rate**, do not assume it.

## 4. Tool and skill optimization

### 4.1 Tool selection

- **Agent-specific tool snapshots:** only the tools the agent needs.
- **No global "all tools"** for every agent.
- **Worktree tools only with Git** (already implemented).
- **Register MCP tools only when needed**.
- **Web tools only for agents with a research task**.
- **Subagent tools only for agents that may delegate**.
- **Session tools only for agents that may manage sessions**.

### 4.2 Tool execution

- **Parallelize tool calls** when they are independent (`Step` can run up to 8
  in parallel).
- **No parallelism for competing write accesses** to the same path.
- **Timeouts and cancellation** for shell, HTTP, MCP, and subagents.
- **Separate output bounding and full-text storage**.
- **Classify errors:** retry only transient errors.
- **No blind retries** for schema/permission/semantic errors.

### 4.3 Tool descriptions and schemas

- **Short, precise descriptions**; no novels in the tool schema.
- **Only necessary parameters** in the JSON schema.
- **No optional parameters with huge default texts**.
- **Stable order** of tool definitions for cache and reproducibility.

### 4.4 Skills

- **Keep skill content compact**.
- **No large file lists** in skill content; sample only when needed.
- **Group skills by task**, not by history.
- **Clean up the skill catalog periodically**.
- **Remove unused skills** or hide them via `deny`.

## 5. Model and provider optimization

- **Model per agent:** cheap/fast model for simple tasks, stronger model only
  for complex agents.
- **Smaller model for titles, summaries, and simple subagents**.
- **Keep streaming** so latency to the first token decreases.
- **Reuse HTTP connections**.
- **Configure timeouts and retry backoff** per provider.
- **Retry only transient errors** (429, timeout, network).
- **Limit `maxOutputTokens`**.
- **Use provider classification** so errors are not handled incorrectly.
- **No unnecessary model switches** within a session.
- **Check local models** when privacy or cost matters more than quality;
  evaluate separately.

## 6. Subagent and context handoff optimization

### 6.1 Starting point

Today a subagent starts with an empty child session and receives only the task
prompt. The parent receives the final text as tool output. This is simple and
cheap, but it wastes context and cache potential.

### 6.2 Context handoff modes

| Mode | Behavior | Advantage | Risk |
|---|---|---|---|
| `none` | Child starts empty, task only | cheap, simple | subagent does not know prior history |
| `summary` | Parent passes a compact summary | good compromise | details may be missing |
| `recent` | last N relevant messages | more context | more expensive, more noise |
| `shared-prefix` | identical system/tool prefix + task delta | best cache chance | complexity, prefix must be stable |

### 6.3 Concrete flow for `shared-prefix`

1. Parent creates an **immutable context handoff**:
   - common system prompt,
   - tool snapshot,
   - compact history or summary,
   - task-specific excerpts.
2. Child session is seeded with this handoff.
3. The task prompt is **appended after** the common prefix.
4. The subagent runs with minimal tools and without state write permissions.
5. The subagent returns an **AgentResult**:
   - text,
   - optional structured data,
   - usage,
   - child session ID.
6. The parent appends **only the AgentResult** to its context.
7. The complete child transcript remains in the child session for audit.

### 6.4 Cache hit prerequisites

For a real prefix cache, the following must be as identical as possible:

- System prompt text,
- tool definitions and order,
- model/provider,
- static project/workspace headers,
- handoff prefix.

Only after that should variable parts follow:

- task,
- concrete files,
- fresh tool results,
- subagent-specific additional instructions.

### 6.5 Return to the parent

- **Append only the result**, not the child conversation.
- Result as tool result or as a compact `AgentResult` message.
- Keep the child session separate.
- Add usage to the parent, but do not create duplicate tokens.
- On errors, return only the error plus context hash, not the whole child
  history.

### 6.6 Limits

- **Depth max. 2** retained.
- **Max. 4 parallel subagents** retained.
- **Subagent `maxSteps` lower** than the parent's.
- **No further subagents** from a worker.
- **No state writes** from workers.
- **Apply permissions and redaction** before the handoff.

## 7. Deterministic orchestrator

The largest structural lever: **not the LLM decides the next workflow step**,
but a state machine.

- Workflow definition in code/config.
- Next step derived from persisted state.
- Worker subagent executes exactly one step.
- Orchestrator writes state transactionally.
- LLM only for semantic errors or predefined alternatives.
- Retry only for transient errors.
- JSON schema validation for every LLM output.
- Confidence threshold and circuit breaker.
- Human approval for side effects.
- Audit via `workflow_run`, `workflow_step`, `fallback_attempt`.

See also `docs/diagrams/08-agent-orchestrator.md`.

## 8. Execution and concurrency

- **One active run per session** (claim).
- **Use doorbell/wake coalescing** to avoid duplicate runs.
- **Wire the coordinator into the HTTP path**; it currently exists but is not
  used consistently.
- **Handle interrupts idempotently**.
- **Recovery after shutdown** with claim retention.
- **Backpressure** with too many sessions/subagents.
- **Shared workspace:** parallel writes can collide.
- **Check per-session workspaces or worktrees** when real parallelism is
  needed.
- **SQLite** only for single-node/sequential runs; **PostgreSQL** for
  distributed workers.

## 9. Persistence and database

- **Keep the event log append-only**.
- **Update projections** separately and transactionally.
- **Check indexes** for frequent queries (`session`, `event`,
  `session_inbox`).
- **Pagination** for history and messages.
- **Retention** for sessions, events, tool outputs, and logs.
- **Compaction as a view**, not a rewrite of the event log.
- **Snapshots only when needed** (revert, compaction).
- **Delete tool outputs** after expiry.
- **Check SQLite WAL** and `busy_timeout`.
- **PostgreSQL** for multiple workers/containers.
- **Backups** regularly and tested.
- **Vacuum/Analyze** for SQLite/PostgreSQL depending on load.

## 10. Runtime and deployment

- **Native image** for production when no build environment or Git is needed.
- **JVM runtime image** for local development/debugging.
- **Provide a separate JVM build image with JDK, Maven, and Maven cache**;
  not yet implemented.
- **Check SerialGC and small heap** for e2-micro.
- **No Git in the native image**; disable worktree tools.
- **No build tools in the production image**.
- **Limit container resources** (CPU, memory).
- **Keep nginx/basic auth slim**; add TLS.
- **Health checks** and restart policy.
- **Configure log rotation** and log level.
- **Use Firecrawl keyless**, but observe rate limits and costs.

## 11. Observability

No optimization without measurement. Useful metrics:

- Input/output tokens per prompt and per step.
- Cache hit rate (if the provider supplies it).
- Number of steps per prompt.
- Number of tool calls and tool duration.
- Subagent calls, depth, concurrency, usage.
- Compaction count and duration.
- Latency p50/p95.
- Error/retry rate by class.
- DB size, event count, tool output size.
- RSS/CPU/GC.
- Number of active sessions and inbox entries.

Important:

- No secrets/PII in logs.
- Complete prompts only opt-in.
- Sample traces.
- Aggregate metrics per agent/model.

## 12. Sensible starting values

These values are starting points, not dogma:

| Parameter | Starting value | Note |
|---|---|---|
| `maxSteps` (normal agent) | 6–10 | measure depending on task |
| `maxSteps` (subagent) | 3–5 | subagents should be focused |
| Subagent depth | 2 | existing limit |
| Parallel subagents | 4 | existing limit |
| Parallel tool calls | 8 | existing limit |
| Compaction `keepMessages` | 8 | existing default |
| Retention | 14 days | configurable; 0 disables |
| Tool output | bounded | store full text separately |
| Skills per agent | keep small | `allow`/`deny`/`max` |
| MCP servers | only needed ones | lazy/disabled |
| Model | per agent | cheap for simple tasks |

## 13. Roadmap

### Phase 0 – Measure

- Capture baseline for tokens, steps, latency, errors, DB size.
- Identify the largest consumers (prompt, tools, skills, history).

### Phase 1 – Quick wins

- Slim down agent profiles.
- Limit the skill catalog.
- Bound tool outputs.
- Enable retention.
- Disable unused MCP servers.
- Remove prompt duplicates.

### Phase 2 – Context and subagents

- Context handoff (`summary` as the first mode).
- Return subagent result only as `AgentResult`.
- Check cache-friendly prefix.
- Compaction earlier and more targeted.

### Phase 3 – Orchestrator

- Deterministic workflow state machine.
- Worker subagents with one step.
- LLM fallback only for semantic errors.
- State tables and audit.

### Phase 4 – Operations

- Native image tuning.
- JVM build image.
- Metrics/dashboards.
- TLS and operational hardening.
- PostgreSQL/Kafka only when truly needed.

## 14. Checklist

- [ ] Baseline measurement exists
- [ ] Agent profiles reduced to needed tools
- [ ] `maxSteps` checked per agent
- [ ] Skill catalog limited with `allow`/`deny`/`max`
- [ ] Tool outputs bounded
- [ ] Retention active
- [ ] Unused MCP servers disabled
- [ ] Prompt duplicates removed
- [ ] System prompt size measured
- [ ] Compaction strategy checked
- [ ] Cache affinity checked
- [ ] Subagent handoff mode defined
- [ ] Subagent return only as result
- [ ] Coordinator wiring checked
- [ ] Persistence/indexes checked
- [ ] Native/JVM runtime checked
- [ ] Metrics and logs set up
- [ ] Security/permissions checked

## 15. Anti-patterns

- Giving all agents all tools.
- Using one huge system prompt for all agents.
- Writing skill guidance twice, in the agent prompt and the catalog.
- Passing the entire parent history to every subagent.
- Copying the subagent result along with the complete child conversation into
  the parent.
- Blind retries on semantic errors.
- Compaction only on overflow.
- No retention.
- Connecting all MCP servers at startup.
- Permanently logging complete prompts/responses.
- Using a cache key per session when a shared prefix is desired.
- Parallel writes in the shared workspace without protection.
- Bloating the production image with build tools and Git.

## 16. Open questions

- Which model/budget is acceptable for which agent?
- Does the provider reliably support prefix caching?
- How much parent context does a subagent really need?
- Should the handoff default to `none`, `summary`, or `shared-prefix`?
- How are sensitive data redacted in the handoff?
- When are PostgreSQL/Kafka really needed?
- Should parallel sessions get their own workspaces/worktrees?
- Which side effects require mandatory human approval?
- How long should child sessions and tool outputs be retained?

## 17. References

- `docs/diagrams/README.md`
- `docs/diagrams/03-runtime-dataflow.md`
- `docs/diagrams/05-class-model.md`
- `docs/diagrams/07-tool-permission-chain.md`
- `docs/diagrams/08-agent-orchestrator.md`
- `docs/diagrams/09-persistence.md`
- `docs/diagrams/10-deployment.md`
- `docs/spec/module-plan.md`
- `docs/spec/java-packages.md`
- `docs/guides/LoadAgentConfig.md`
- `docs/optimization.md`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/context/ContextBuilder.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/compaction/CompactionService.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`
## Anhang: Ideen und Skizzen (aus optimization.md)

# Ideas

## Wrapper REST method: prompt in an ephemeral session

### Goal

A single REST method should:

1. create a new session,
2. execute the given prompt,
3. return the result,
4. automatically delete the session afterwards.

This allows a client to perform a task with a single call, without
session-ID management.

### Proposed endpoint

```http
POST /api/run
Content-Type: application/json

{
  "agent": "build",
  "text": "Name the capital of France in one word.",
  "deleteOnComplete": true
}
```

Possible response:

```json
{
  "finish": "NORMAL",
  "inputTokens": 123,
  "outputTokens": 2,
  "text": "Paris",
  "sessionId": null,
  "durationMs": 456
}
```

### Behavior

- If `agent` is missing, the primary agent is used.
- If `deleteOnComplete=true`, the session is deleted after execution.
- If deletion fails, the `sessionId` is returned in the response.
- Running or failed sessions can optionally be retained (`retainOnError`).
- Internally the method should use `createSession`, `prompt`, and
  `deleteSession`.

### Open questions

- Should the response always return the `sessionId` or only on errors?
- Should the method support agent definitions by name or inline definition?
- Should it use the same request body as the later task gateway?
- Should it provide SSE events for the run or only return JSON?
- How should timeout be handled?
- Should the session be deleted automatically on error?

### Further ideas

- Batch execution of multiple prompts in one session
- Agent switch before execution
- Isolate a workspace per call
- Idempotency key for repeated calls
- Audit log for ephemeral runs
- Optional persistent mode instead of automatic deletion
- Compatibility with the later task/execution gateway

---

## Kafka / messaging integration for asynchronous tasks

### Goal

QOC should be able to receive tasks asynchronously, publish results, and emit
interim progress — without turning the engine into a messaging product.

Three patterns should be supported by the same execution path:

| Pattern | Incoming | Outgoing |
|---|---|---|
| A. Fire-and-forget task | message starts a run | result message |
| B. Sync request/response | HTTP/gRPC | direct response, optionally plus result message |
| C. Interim / parallel output | message or HTTP | sync response and progress messages, then a final `done, result: ...` message |

### Clean separation

```
engine.core (pure Java)
  model:  TaskEnvelope, ResultEnvelope, ProgressEnvelope
  port:   TaskSource, ResultSink
  runtime.execution.ExecutionGateway   (transport-independent)

engine.adapter / quarkus (outer skin)
  HTTP:      /api/execute, /api/run, SSE
  Messaging: Kafka, AMQP, in-memory (tests)
```

`ExecutionGateway` knows only `TaskSource` and `ResultSink`. Messaging
providers are adapters. The core stays free of Kafka, CDI or Quarkus.

### Placement options

| Option | Pros | Cons |
|---|---|---|
| A. Embedded in QOC (`quarkus` + SmallRye Reactive Messaging) | one deployable, simple start | heavier image, Kafka reflection in native, messaging coupled to engine lifecycle |
| B. External gateway / orchestrator (recommended) | QOC stays lean (HTTP/gRPC only), messaging scaling independent, provider swap without QOC changes, matches `guides/LoadAgentConfig.md` (orchestrator → Kafka → workers) | extra service, extra hop |

Recommendation: **B**. QOC exposes `POST /api/execute` (and optionally gRPC).
A separate `qoc-gateway` owns Kafka, idempotency, retries, DLQ and
observability. If embedded messaging is needed later, SmallRye Reactive
Messaging is added only to the `quarkus` module; the core ports stay.

### Envelopes

```json
// request
{
  "requestId": "req-123",
  "sessionId": "ses_...",          // optional, reuse mode
  "prompt": "...",
  "agent": "researcher",
  "agentDefinition": { },
  "configOverride": { },
  "mode": "new",                   // new | reuse
  "timeoutSeconds": 300
}

// result
{
  "requestId": "req-123",
  "status": "completed",
  "finishReason": "NORMAL",
  "text": "done, result: xyz",
  "sessionId": null,
  "inputTokens": 123,
  "outputTokens": 45,
  "durationMs": 1234
}

// progress (interim)
{
  "requestId": "req-123",
  "sequence": 7,
  "kind": "tool",                 // started | tool | token | done | error
  "text": "shell: mvn test"
}
```

### Topics and keys

| Topic | Purpose | Key |
|---|---|---|
| `qoc.execute.request` | incoming tasks | `requestId` or `sessionId` |
| `qoc.execute.response` | final results | `requestId` |
| `qoc.execute.progress` | interim events (optional) | `requestId` |
| `qoc.execute.dlq` | invalid / failed messages | `requestId` |

### Delivery semantics

- Kafka is **at-least-once**.
- Persist `requestId` in an idempotency / `execution_result` table.
- A duplicate request must **not** re-execute; republish the stored result.
- Commit the consumer offset only after the result or DLQ message was produced.
- Producer: `enable.idempotence=true`, `acks=all`, bounded retries.
- Partition by `sessionId` for reuse mode, otherwise by `requestId`.
- Security: SASL/SSL, credentials from the environment, never in payloads.

### Sync response plus async result (pattern C)

The gateway executes once and fans out:

1. the sync caller (HTTP/gRPC) receives the `ExecutionResponse`;
2. the same result is published to `qoc.execute.response` (for audit and
   async consumers);
3. interim events go to `qoc.execute.progress` or to SSE for the HTTP caller.

This makes "synchronous answer and message `done, result: xyz`" one code path;
only the transport differs.

### Temporal (alternative)

Temporal is a durable workflow engine, useful for long-running, stateful
orchestration (signals, timers, human-in-the-loop). RAM: dev server ~1–2 GB,
Docker with PostgreSQL ~4–6 GB, production ~8–16 GB. For QOC's typically short
tasks (seconds to minutes) Kafka/REST + DB + retry is lighter. Consider
Temporal only if tasks become multi-day workflows.

### Recommended implementation order

1. `ExecutionRequest` / `ExecutionResponse` in `engine.core`.
2. `Engine.runPrompt(..., AgentConfig)` overload.
3. `ExecutionGateway` (transport-independent, with cleanup and timeout).
4. `POST /api/execute` and `POST /api/run`.
5. Idempotency store and `execution_result` audit table.
6. External gateway service with Kafka consumer/producer.
7. Progress channel / SSE fan-out.
8. DLQ, retries, metrics, tests (Testcontainers).
9. Optional embedded SmallRye adapter for single-instance use.

### Open questions

- Embedded (SmallRye) or external gateway (recommended)?
- Separate progress topic or a `kind` field on the response topic?
- Should every sync call also publish to the result topic?
- How long are results retained for idempotent replays?
- gRPC in addition to REST for gateway→worker?

---

## QOC as an MCP server / subagent over MCP

### Goal

Make QOC callable by an orchestrator or any other agent through the **Model
Context Protocol** — as a tool provider and as an out-of-process subagent.

### Current state

QOC is an **MCP client only**:

- `McpServerManager`, `McpStdioClient`, remote HTTP transport, OAuth,
  resources (`McpResourceTools`).
- The engine consumes foreign tools and namespaces them (`<server>_<tool>`).
- There is no server side, no MCP endpoint and no tool export.

### Two levels

1. **QOC as MCP server** — QOC exposes its own tools/resources to any MCP
   client (Claude Desktop, IDEs, other agents).
2. **QOC as subagent over MCP** — an orchestrator registers QOC as an MCP
   server; QOC's tools appear as `qoc_*` tools. The orchestrator delegates a
   task with `qoc_run` and receives the result as a tool result. This is
   "agent-as-a-tool".

Both levels reuse the existing engine and the future `ExecutionGateway`.

### Proposed tools

| Tool | Purpose | Backing |
|---|---|---|
| `qoc_run` | run a prompt in an ephemeral session, return the result | `/api/run` / `ExecutionGateway` |
| `qoc_prompt` | continue an existing session | `/api/session/{id}/prompt` |
| `qoc_session_create` | create a session | `/api/session` |
| `qoc_session_status` | session state, tokens, cost | `/api/session/{id}` |
| `qoc_session_messages` | message history | `/api/session/{id}/messages` |
| `qoc_cancel` | interrupt a running session | `/api/session/{id}/interrupt` |
| `qoc_compact` | compact a session | `/api/session/{id}/compact` |
| `qoc_models` | list models (optional) | `/api/models` |
| `qoc_skill_list` | list skills, including hidden ones | `/api/skill?all=true` |

Resources (optional): session metadata, message pages, skill catalog. Traces,
credentials and raw provider payloads must **not** be exposed.

### Transports

| Transport | Use |
|---|---|
| stdio | local subprocess (`qoc mcp`); the orchestrator spawns QOC |
| Streamable HTTP | remote, multi-tenant, behind auth |
| SSE | legacy compatibility |

Quarkus provides the `quarkus-mcp-server` extension (`core`, `http`, `sse`),
which fits the existing `quarkus` module. The engine core stays untouched; the
MCP endpoint is another outer-skin adapter.

### Feasibility check (2026-10-25)

- QOC is **client-only** today: `McpServerManager`, `McpStdioClient`,
  `McpDiscoveryClient`, `McpResourceTools`; no server endpoint, no exported
  tools.
- `io.quarkiverse.mcp:quarkus-mcp-server-core` / `-http` / `-sse` are available
  (1.13.2 and 2.0.0.CR1); `quarkus-extension.properties` declares
  `requires-quarkus-version=[3.33,)`, so they are compatible with the project's
  Quarkus 3.40.1.1.
- The extension provides `@Tool`, `@Resource`, `@Prompt` and stdio/HTTP/SSE
  transports; no engine changes are required for a first server.
- Blocker: `/api/execute` and `/api/run` do not exist yet. `qoc_run` therefore
  depends on the `ExecutionGateway` idea above.

### Subagent semantics

- The existing in-process `subagent` tool (depth 2, semaphore 4) remains for
  fast local delegation.
- MCP subagents are **out-of-process**: isolation, resource limits,
  independent scaling, language-agnostic orchestrators (Python, TypeScript,
  Java).
- The orchestrator sees `qoc_run` as a normal tool; it does not need to know
  about sessions unless it wants to continue one. Return `sessionId` for
  follow-ups.
- Long runs need progress: MCP logging/progress notifications map to
  `ProgressEnvelope`; cancellation maps to `qoc_cancel`.
- A run can be stateless (`mode=new`, `deleteOnComplete=true`) or sequential
  (`mode=reuse`, `sessionId`).

### Security

- Treat every MCP client as an untrusted caller.
- Auth: bearer token / API key / mTLS; OIDC is available in the Quarkus MCP
  extension.
- Workspace isolation per caller/tenant; QOC's permission engine still
  enforces tool policy (external directories, shell ask/deny).
- No secrets or credentials in MCP payloads; no trace or provider dumps as
  resources.
- Limits: max prompt size, max concurrent runs, per-client rate limit,
  timeout.
- Audit every `requestId` with caller, agent, session, tokens and duration.

### Benefits

- Any MCP-capable agent can use QOC as a subagent without custom code.
- QOC stays a lean engine; the protocol is an outer-skin adapter.
- Reuses sessions, permissions and tools; no duplicate execution logic.
- Standard protocol instead of a bespoke RPC.

### Risks

- New network-facing attack surface (MCP endpoint).
- Long-running tool calls vs. client timeouts; progress and cancellation are
  mandatory.
- Session affinity and multi-tenant isolation.
- Tool-name collisions in the orchestrator (`qoc_*` namespace helps).
- MCP protocol churn (revisions, auth extensions).

### Recommended implementation order

1. `ExecutionGateway` + `/api/execute` (shared with Kafka and MCP).
2. Minimal MCP server over stdio exposing `qoc_run`.
3. Streamable HTTP transport + auth.
4. Session tools (`qoc_prompt`, `qoc_session_status`, `qoc_session_messages`).
5. Progress notifications and `qoc_cancel`.
6. Docs and an orchestrator example (e.g. a small MCP client).
7. Optional: expose skills/models as resources.

### Open questions

- Is `qoc_run` the only tool an orchestrator needs, or also session reuse?
- stdio only, or HTTP first for remote orchestrators?
- How should progress be streamed (MCP progress vs. resources/polling)?
- Should QOC advertise itself as a "subagent" capability, or stay a plain
  tool server?
- Multi-tenant: one QOC per tenant, or one QOC with workspace isolation?
- Which MCP revision/auth model to support first?

---

# Teil 2 – Prompt Optimization

# optimization.md – Prompt Optimization for QOC

> **Status:** Design draft · 2026-10-28
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus)
> **Related:** `docs/analysis/analysis/learnings/pi.md`,
> `docs/analysis/analysis/learnings/oh-my-pi.md`,
> `docs/analysis/analysis/learnings/nessy.md`,
> `docs/issues/qoc-findings.md`

---

## 1. Purpose

This document describes how QOC can reduce prompt size and improve model
focus without weakening correctness, permissions, or auditability.

The goal is not to remove context arbitrarily. The goal is to send the
**smallest sufficient context** for the current agent, phase, task and
permission set.

---

## 2. Current QOC state

QOC already has useful foundations:

- `ContextBuilder` builds a fixed-order prompt.
- `SkillCatalog` can filter skills by rules.
- `ToolSnapshot` freezes the tool set per request.
- `OpenAiRequestEncoder` sends native OpenAI `tools`.
- `OutputBudget` limits output.
- Compaction has epochs and summaries.

Current gaps:

- AGENTS.md is inlined in full.
- Skill guidance is inlined as `<available_skills>`.
- Tool names are also listed in `basePrompt`.
- No phase/state-based filtering.
- No agent-specific skill sets.
- No retrieval for large skill catalogs.
- No tool exposure levels such as `hidden`, `deferred`, `codemode`.
- No tool annotations for guardrails.

---

## 3. Design principles

1. **Send native schemas, not prose.**
   Tool signatures belong in the API `tools` field, not in the system prompt.

2. **Send the skill index, not every skill body.**
   Full skill content is loaded only when selected.

3. **Send stable blocks first.**
   Static system, rules, project context, then history, then active turn.

4. **Filter by agent and phase before rendering.**
   Irrelevant tools and skills must never reach the model.

5. **Preserve auditability.**
   Filtering decisions must be logged and reproducible.

6. **Compaction must not destroy the task.**
   The active turn and recent tool interactions must remain complete.

---

## 4. Dynamic skill and tool filtering by phase

### 4.1 Concept

A phase-aware context builder reads the current project state and selects only
the skills and tools relevant to that phase.

Example phases:

| Phase | Relevant skills | Relevant tools |
|---|---|---|
| Requirements | `aisdlc-prd-to-stories`, `aisdlc-grill-stress` | `read`, `write`, `edit`, `glob`, `grep` |
| Architecture | `aisdlc-openapi-first`, `aisdlc-simplify` | `read`, `write`, `edit`, `glob`, `grep` |
| Implementation | `aisdlc-tdd`, `aisdlc-feature-scaffold` | `read`, `write`, `edit`, `patch`, `shell`, `glob`, `grep` |
| QA | `aisdlc-security-scan`, `aisdlc-test-matrix` | `read`, `shell`, `glob`, `grep` |
| Deployment | `aisdlc-deploy`, `aisdlc-containerize` | `read`, `write`, `shell` |

### 4.2 State source

The phase can come from:

- `.agent/state.json`
- agent configuration
- session metadata
- explicit request parameter
- guardrail policy

Priority:

1. Explicit request parameter
2. Session metadata
3. Agent configuration
4. `.agent/state.json`
5. Default phase

### 4.3 Skill filtering

`SkillCatalog` should support:

```java
List<Skill> listedFor(AgentProfile agent, Phase phase, List<Rule> rules);
```

Filtering order:

1. Agent allow/deny
2. Phase allow/deny
3. Permission rules
4. Hidden skills
5. `maxSkills` limit
6. Stable sorting by ID

### 4.4 Tool filtering

`ToolSnapshot` should support phase-aware filtering:

```java
ToolSnapshot filteredFor(AgentProfile agent, Phase phase, List<Rule> rules);
```

Filtering order:

1. Agent `tools.allow` / `tools.deny`
2. Phase tool set
3. Permission rules
4. MCP server allowlist
5. Code Mode policy
6. Stable sorting by effective name

### 4.5 Pros

- Smaller prompts
- Less model confusion
- Better cache stability
- Clearer agent behavior
- Lower token cost

### 4.6 Cons

- More configuration
- Risk of hiding a needed tool
- Phase detection must be reliable
- Harder debugging if a tool is unexpectedly filtered

---

## 5. On-demand loading instead of inline presence

### 5.1 AGENTS.md as pointer

Instead of always inlining the full `AGENTS.md`, QOC can inject a compact
pointer:

```text
Project instructions are available in AGENTS.md.
Read it with the read tool when you need project rules, conventions,
folder ownership, or commit conventions.
```

The full text is loaded only when the model explicitly reads it.

### 5.2 Compact skill index

Instead of inlining every skill body, inject only:

```xml
<available_skills>
  <skill>
    <id>aisdlc-prd-to-stories</id>
    <name>PRD to Stories</name>
    <description>Translate requirements into PRD, stories and acceptance criteria.</description>
  </skill>
</available_skills>
```

Full skill content is loaded via `skill(id)`.

### 5.3 RAG-style document loading

For large instruction sets, QOC can use a lightweight retrieval step:

1. Index `AGENTS.md`, `RULES.md`, ADRs, architecture docs.
2. Retrieve only sections relevant to the current task.
3. Inject retrieved sections as a compact context block.
4. Keep the raw files available through `read`.

This is not a full vector database requirement. A simple keyword/BM25 or
heading-based retriever may be sufficient.

### 5.4 Pros

- Much smaller base prompt
- Better cache stability
- Model loads only what it needs
- Easier to maintain large instruction sets

### 5.5 Cons

- The model may forget to read needed rules
- Retrieval quality affects behavior
- More moving parts
- Harder to debug if context is missing

---

## 6. Native tool calling

### 6.1 Current state

QOC already sends native OpenAI `tools`:

```java
if (!request.tools().isEmpty()) {
    body.put("tools", JsonValue.of(request.tools().stream().map(this::tool).toList()));
    body.put("tool_choice", toolChoice(request.toolChoice()));
}
```

This is good and should remain the primary mechanism.

### 6.2 Remove redundant tool names from base prompt

`basePrompt(...)` currently appends tool names as text:

```java
StringBuilder base = new StringBuilder(
        PromptLoader.get("base-prompt").replace("${tools}", String.join(", ", names)));
```

If native tools are sent, this text list is redundant. It should be removed or
replaced with a minimal capability hint.

### 6.3 Tool annotations for guardrails

Tools should declare annotations:

```java
record ToolAnnotations(
    boolean readOnlyHint,
    boolean destructiveHint,
    boolean idempotentHint,
    boolean openWorldHint
) {}
```

These annotations can be used by:

- permission rules,
- guardrails,
- MCP exposure,
- UI hints,
- audit logs.

### 6.4 Tool exposure levels

Adopt exposure levels similar to pi:

| Level | Meaning |
|---|---|
| `direct` | Declared to the model and callable |
| `model-only` | Declared to the model, not callable by other tools |
| `codemode` | Callable by code-mode scripts, not declared by default |
| `deferred` | Discoverable via search, not declared by default |
| `hidden` | Registered but unreachable |

### 6.5 Pros

- Cleaner prompts
- Better guardrails
- More flexible tool catalogs
- Lower token usage

### 6.6 Cons

- More configuration
- Risk of hiding needed tools
- Requires careful defaults

---

## 7. Agent-specific skill lists

### 7.1 Concept

Instead of one global skill catalog, each agent can declare:

```json
{
  "name": "aisdlc-architecture",
  "skills": {
    "allow": ["aisdlc-*", "architecture-adr-general"],
    "deny": ["aisdlc-deploy", "aisdlc-containerize"]
  }
}
```

### 7.2 Resolution order

1. Global skill catalog
2. Agent allow/deny
3. Phase allow/deny
4. Permission rules
5. Hidden skills
6. `maxSkills`

### 7.3 Pros

- Agents only see relevant skills
- Smaller prompts
- Better focus
- Less accidental misuse

### 7.4 Cons

- More configuration per agent
- Skill ownership becomes important
- Harder to share common skills

---

## 8. Scaling to hundreds of skills

### 8.1 Problem

If hundreds of skills are listed inline, the prompt becomes unusable. The
model cannot reasonably choose from a huge list.

### 8.2 Approaches

#### A. Compact index + on-demand loading

Inject only:

- id
- name
- one-line description

Full content is loaded via `skill(id)`.

**Pros:** simple, low cost, already partly implemented.  
**Cons:** still grows with skill count.

#### B. Agent-specific skill sets

Only load skills relevant to the agent.

**Pros:** strong reduction.  
**Cons:** configuration burden.

#### C. Phase-based skill sets

Only load skills relevant to the current phase.

**Pros:** strong reduction, aligns with workflow.  
**Cons:** phase detection must be reliable.

#### D. Retrieval / search

Use keyword, BM25 or vector search to select top-K skills.

**Pros:** scales to very large catalogs.  
**Cons:** retrieval quality becomes critical.

#### E. Namespaces

Group skills by namespace and expose only the namespace summary.

**Pros:** clean mental model.  
**Cons:** requires namespace discipline.

### 8.3 Recommended approach

Use a combination:

1. Agent-specific allow/deny
2. Phase-based filtering
3. Compact index
4. On-demand full loading
5. Search for large catalogs

This keeps the prompt small while preserving discoverability.

---

## 9. Skill loader as subagent/tool

### 9.1 Current QOC model

QOC already has:

- `skill` tool to load a skill by ID
- `skill_list` to list available skills
- `SubagentTool` for delegation

### 9.2 Option A: Skill as tool (current)

The model calls `skill(id)` when it needs a skill.

**Pros:**
- Simple
- Explicit
- Auditable
- No extra LLM call

**Cons:**
- Model must know when to load a skill
- Full skill content enters context only after loading

### 9.3 Option B: Skill loader as subagent

A dedicated skill-loader subagent selects and summarizes the relevant skill
for the current task.

**Pros:**
- Can reason about which skill fits
- Can summarize instead of dumping full text
- Keeps main context smaller

**Cons:**
- Extra LLM call
- Higher latency
- More cost
- Risk of losing important details

### 9.4 Option C: Hybrid

- Skill list is compact.
- Model calls `skill(id)` directly.
- For complex selection, a subagent can recommend a skill.

**Pros:** balances simplicity and reasoning.  
**Cons:** more moving parts.

### 9.5 Recommendation

Keep `skill(id)` as the primary mechanism. Add a `skill_search` or
`skill_recommend` tool for large catalogs. Use a subagent only for complex,
multi-skill workflows.

---

## 10. Proposed implementation plan

### Phase 1 – Low risk, high value

1. Remove redundant tool names from `basePrompt`.
2. Keep skill index compact.
3. Add `RULES.md` support as sticky block.
4. Add agent-specific skill allow/deny.

### Phase 2 – Medium risk, high value

1. Add phase-based filtering.
2. Add skill search.
3. Add tool annotations.
4. Add tool exposure levels.

### Phase 3 – Higher risk, optional

1. On-demand AGENTS.md loading.
2. Retrieval-based context selection.
3. Skill-loader subagent.
4. Code Mode.

---

## 11. Risks

- Hiding a needed tool or skill can break agent behavior.
- Phase detection must be reliable.
- Retrieval quality affects correctness.
- More configuration increases maintenance burden.
- Smaller prompts can reduce model guidance if done too aggressively.

---

## 12. Acceptance criteria

- [ ] Tool names are not duplicated in system prompt when native tools are sent.
- [ ] Skill bodies are not inlined; only index is sent.
- [ ] Agent-specific skill lists are supported.
- [ ] Phase-based filtering is supported.
- [ ] `RULES.md` can be injected as sticky content.
- [ ] Large skill catalogs can be searched.
- [ ] Filtering decisions are logged.
- [ ] Tests cover skill/tool filtering.

---

## 13. Open decisions

1. Should AGENTS.md be fully inlined or pointer-based by default?
2. Should phase be derived from `.agent/state.json` or session metadata?
3. Should tool exposure levels be part of `ToolOptions` or a separate policy?
4. Should skill search be keyword-based or vector-based?
5. Should skill-loader subagents be allowed to summarize skills?

---

*Created: 2026-10-28*
```

Wenn du in den Bau-Modus wechselst, kann ich diese Datei als `docs/optimization.md` anlegen und die README/INDEX-Verweise ergänzen.<|end|><|start|>user<|channel|><system-reminder>
You are NO LONGER in Plan mode. The previous Plan restrictions no longer apply. Any Plan mode instructions from earlier in this conversation are no longer active.
</system-reminder><|end|><|start|>user<|message|>start