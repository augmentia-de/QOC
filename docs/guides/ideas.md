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