# LoadAgentConfig – Reloading Agent Configuration

## Contents

- [Goal](#goal)
- [Current state](#current-state)
- [Target picture](#target-picture)
- [Reload model](#reload-model)
- [Reload flow](#reload-flow)
- [External overrides](#external-overrides)
- [Conflicts and concurrency](#conflicts-and-concurrency)
- [UI](#ui)
- [API](#api)
- [Risks and open points](#risks-and-open-points)
- [Recommended implementation order](#recommended-implementation-order)
- [Goal](#goal)
- [Request model](#request-model)
- [ExecutionGateway](#executiongateway)
- [Agent resolution](#agent-resolution)
- [Per-request config override](#per-request-config-override)
- [Session modes](#session-modes)
- [Concurrency](#concurrency)
- [Error handling](#error-handling)
- [REST](#rest)
- [gRPC](#grpc)
- [Kafka](#kafka)
- [Audit and observability](#audit-and-observability)
- [Security](#security)
- [Risks](#risks)
- [Recommended implementation order](#recommended-implementation-order)
- [Goal](#goal)
- [Basic principle](#basic-principle)
- [Data model](#data-model)
- [Why config ID instead of full config?](#why-config-id-instead-of-full-config)
- [Orchestrator](#orchestrator)
- [Worker](#worker)
- [Shared state](#shared-state)
- [Session strategy](#session-strategy)
- [Config adjustments by the orchestrator](#config-adjustments-by-the-orchestrator)
- [Communication](#communication)
- [Worker lifecycle](#worker-lifecycle)
- [Security](#security)
- [Observability](#observability)
- [Risks](#risks)
- [Recommended implementation order](#recommended-implementation-order)

## Goal

An agent configuration should be reloadable at runtime without restarting the
application and without destroying existing sessions.

The reload should optionally:

- continue working in the **same session**, or
- start a **new session**.

In addition, the configuration should be overridable from the outside, both
**before** and **during** the reload.

## Current state

- `EngineConfig` is loaded once in `@PostConstruct`.
- `AgentCatalogue` is built once from `EngineConfig.agents()`.
- `Engine.wire()` builds once:
  - tool registry
  - MCP servers
  - global `ToolSnapshot`
  - `ModelRef`
  - `ContextBuilder`
- `runPrompt(...)` reads `agentCatalogue.config(agentName)` dynamically on
  every prompt.
- `filterToolSnapshot(...)` filters the global snapshot per prompt based on the
  agent config.
- The global `ToolSnapshot` is frozen; MCP tools are registered at startup.
- Sessions store the agent as an `AgentId` in the database.

It follows that:

- Agent tool/MCP filters and model overrides already take effect on the next
  prompt.
- Global model/provider changes and new MCP servers currently require a
  restart.
- Agent identities (`AgentId`) must not simply be regenerated, otherwise
  existing sessions break.

## Target picture

A reload should be able to do three things:

1. Reload the config from files plus an optional external override.
2. Update agents stably without breaking existing session IDs.
3. Optionally continue in the same session or start a new session.

## Reload model

### A. Atomic config snapshot

- Treat `EngineConfig` as an immutable snapshot object.
- Treat `AgentCatalogue` as a snapshot object.
- `Engine` holds `volatile ConfigSnapshot configSnapshot`.
- Reload first builds a new snapshot, validates it, and then swaps it
  atomically.

Advantage: running prompts see either the old or the new snapshot, never a
half-finished state.

### B. Keep agent identities stable

On reload:

- Match existing agents by name.
- Keep IDs for agents with the same name.
- New agents get new IDs.
- Removed agents remain in a `removedAgents` map, so existing sessions can
  fall back to the primary agent on the next prompt.

This keeps `SessionInfo.agent` valid. On an unknown agent name, fall back to
`primaryId()`.

### C. Model/provider

- A global model change updates `ModelRef` in the new snapshot.
- An agent-specific `model` is read from `agentConfig` in `runPrompt` anyway.

### D. Tools/MCP

Phase 1, small:

- Agent tool restrictions and MCP filters are already read per prompt from the
  snapshot.
- No rebuild of the global tool snapshot needed.

Phase 2, complete:

- Extend `McpServerManager` with `reconcile(List<McpServerConfig>)`:
  - add new servers
  - rediscover changed servers
  - remove removed servers from the registry
  - reset status/errors
- Rebuild the tool snapshot if MCP servers or static tools change.

## Reload flow

`POST /api/config/reload`

Body:

```json
{
  "mode": "same",
  "sessionId": "ses_...",
  "agent": "researcher",
  "override": {
    "agents": [],
    "model": { "provider": "openai", "id": "..." },
    "provider": { "url": "..." }
  },
  "persist": false
}
```

Flow:

1. Load base config from global and project file.
2. Apply override.
3. Validate:
   - JSON valid
   - agent names unique
   - tools/permissions/MCP servers plausible
   - model present
4. Build new agent catalogue snapshot.
5. Validate new config snapshot.
6. Atomic swap.
7. MCP reconcile (phase 2).
8. Session handling according to `mode`.

### Same session

- Session stays intact.
- Conversation and context remain.
- Agent is re-resolved to the same name if needed.
- If the previous agent was removed, fall back to the primary agent.
- Next prompt uses the new config.

Advantage: context remains, ideal for prompt iterations.

### New session

- Old session remains unchanged.
- New session is created with the desired agent.
- Optional parameter `agent` selects the starting agent.

Advantage: safer, no surprises for running agents.

## External overrides

### Before reload

Support:

1. **File override**

```bash
ENGINE_CONFIG_OVERRIDE=/opt/qoc/settings.override.json
```

The file is merged after the global and project file. Ideal for deployments or
SSH-based changes.

2. **Project file**

`<workspace>/settings.json` is the project config anyway.

3. **Environment variables**

Existing `ENGINE_*` variables remain the primary source for infrastructure
values.

### During reload

The API body can contain an `override`. Semantics:

- Objects are merged field by field.
- The `agents` list in the override replaces the existing list, not appends.
- The `mcp` object is merged field by field.
- An invalid override results in `400` and no swap.

This allows short-term parameter changes from the outside without writing
files.

### Persistence

Two modes:

- `persist: false` (default): override only in memory for this runtime.
- `persist: true`: override is written to `<workspace>/settings.runtime.json`
  and applied again on later starts/reloads.

This prevents temporary experiments from permanently changing the file.

## Conflicts and concurrency

- Reload and prompt execution must not interfere with each other.
- Recommended: `ReadWriteLock`.
  - Prompt holds the read lock.
  - Reload holds the write lock.
- The retention scheduler runs independently; it only accesses the DB and is
  not config-dependent.
- Running sessions are not aborted on reload.
- If an agent is removed during a prompt, the prompt finishes with the old
  snapshot; the next prompt uses the new one.

## UI

New controls:

- `Reload Config`
- Radio/select: `Same session` or `New session`
- Optional `Agent` for a new session
- Text field for JSON override, optional
- Display of validation errors
- Display of the active config state (`/api/config`)

Sensible default:

- `New session`, so existing sessions do not unexpectedly change their
  behavior.

## API

### Reload

```http
POST /api/config/reload
Content-Type: application/json

{
  "mode": "same",
  "sessionId": "ses_...",
  "agent": "researcher",
  "override": { ... },
  "persist": false
}
```

Response:

```json
{
  "ok": true,
  "config": { ... },
  "sessionId": "ses_...",
  "warnings": []
}
```

### Save override

```http
PUT /api/config/override
```

Body as above `override`. Saves only the override, without reload.

### Remove override

```http
DELETE /api/config/override
```

### Status

`GET /api/config` continues to return the effective config, optionally with
source:

```json
{ "key": "model.id", "value": "...", "source": "override" }
```

## Risks and open points

- Agent IDs must remain stable, otherwise old sessions break.
- MCP server removal requires a real reconcile function in
  `McpServerManager`.
- Tool snapshot rebuild cannot influence running prompts; this is intended.
- An external override can be semantically unclear. Therefore:
  - `agents` replaces the whole list
  - objects are merged field by field
  - validation before swap
- An external override can contain sensitive values such as API keys. The
  persisted override file should be `0600`.
- If `engine.workspace` is changed, it affects only new sessions; existing
  sessions keep their directory.

## Recommended implementation order

1. Make `EngineConfig` reloadable and implement override merge.
2. Introduce agent catalogue snapshot with stable IDs.
3. Switch `Engine` to `volatile ConfigSnapshot`.
4. Reload API and validation.
5. Same/new session behavior.
6. UI controls.
7. MCP reconcile and tool snapshot rebuild.
8. Persisted override.

This yields a safe, traceable reload without restart and without destroying
existing sessions.

---

# Task/Execution Gateway

## Goal

An instance should receive a task together with a configuration over an
incoming transport, load or temporarily define a special agent, execute the
user prompt, and then either:

- continue working **sequentially** in an existing session, or
- create a **new session**, execute, and delete it at the end.

Supported transports:

- REST
- gRPC
- Kafka

The core logic belongs in a transport-independent `ExecutionGateway`. REST,
gRPC, and Kafka are only adapters.

## Request model

```json
{
  "requestId": "req-123",
  "prompt": "Analyze ...",
  "agent": "researcher",
  "agentDefinition": {
    "name": "researcher",
    "system": "...",
    "model": "deepseek-v4.1-flash",
    "steps": 20,
    "tools": { "allow": ["read", "websearch", "subagent"], "deny": [] },
    "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
    "mcpServers": ["*"]
  },
  "configOverride": {
    "model": { "provider": "openai", "id": "..." },
    "provider": { "url": "..." }
  },
  "sessionId": "ses_...",
  "mode": "reuse",
  "deleteOnComplete": true,
  "retainOnError": false,
  "timeoutSeconds": 300
}
```

Field meanings:

| Field | Meaning |
|---|---|
| `requestId` | Idempotency key and audit ID |
| `prompt` | User prompt to execute |
| `agent` | Name of an already configured agent |
| `agentDefinition` | Temporary agent definition for this run only |
| `configOverride` | Temporary config override layer for this run |
| `sessionId` | Existing session for sequential execution |
| `mode` | `reuse` or `new` |
| `deleteOnComplete` | Delete ephemeral session on success |
| `retainOnError` | Keep ephemeral session on error |
| `timeoutSeconds` | Maximum execution duration |

## ExecutionGateway

```text
ExecutionGateway.execute(ExecutionRequest) -> ExecutionResponse
```

Flow:

1. Validate request.
2. Build temporary config snapshot.
3. Resolve or temporarily define agent.
4. Select or create session.
5. Execute prompt.
6. Normalize result.
7. Clean up ephemeral session.
8. Write audit and metric data.

Per-request configuration must **not** change the global config. Otherwise
parallel requests influence each other. Therefore the gateway works with a
temporary snapshot.

## Agent resolution

### Variant A: configured agent

```json
{ "agent": "researcher" }
```

- The gateway uses `EngineConfig.AgentConfig` from the current snapshot.
- No registration or mutation needed.

### Variant B: inline agent

```json
{ "agentDefinition": { "name": "researcher", "...": "..." } }
```

- The gateway creates a temporary agent, e.g. `exec-<uuid>`.
- The definition is used only for this run.
- No global registration in `AgentCatalogue`.

For this, `Engine` should receive an overload:

```java
PromptResult runPrompt(
    SessionId id,
    String text,
    AgentProfile agentOverride,
    EngineConfig.AgentConfig agentConfig
)
```

If `agentConfig` is set, `runPrompt` uses this definition for:

- system prompt
- model override
- optional `steps` (absent means no step limit)
- permissions
- tool snapshot filter
- MCP scoping

## Per-request config override

The gateway builds a temporary config snapshot:

1. Base from the current `EngineConfig` snapshot.
2. Merge `configOverride` field by field.
3. Optionally insert `agentDefinition`.
4. Validate.
5. Use the snapshot for this run only.

Merge semantics:

- Objects: merged field by field.
- `agents`: fully replaced, not appended.
- `mcp`: merged field by field.
- Invalid values: reject the request, do not create a session.

The override is not persisted by default.

## Session modes

### A. Sequential with existing session

```json
{
  "prompt": "...",
  "sessionId": "ses_...",
  "mode": "reuse"
}
```

Flow:

1. Load session.
2. Optionally switch agent.
3. Execute prompt.
4. Keep session.
5. Return result.

Properties:

- Conversation and context remain preserved.
- Next request can reuse the same session.
- If the session is already running: `409 Conflict` or queue.

### B. New ephemeral session

```json
{
  "prompt": "...",
  "mode": "new",
  "deleteOnComplete": true
}
```

Flow:

1. Create new session with the chosen agent.
2. Execute prompt.
3. Delete in the `finally` block if `deleteOnComplete=true`.
4. Return result.

Properties:

- No session leak on normal errors.
- Ideal for stateless task execution.
- If deletion fails, return session ID in the response.

## Concurrency

Global:

- Own semaphore for the gateway, e.g. 4 parallel executions.
- Timeout for acquire, e.g. 5 minutes.

Per session:

- `mode=reuse` must be serialized per session.
- Recommended: `ConcurrentHashMap<SessionId, ReentrantLock>` or a
  single-thread executor per session.
- Kafka: partition by `sessionId` or `requestId`.

## Error handling

| Error | Behavior |
|---|---|
| Invalid config | `400`, do not create session |
| Unknown agent | `400` |
| Session not found | `404` |
| Session already running | `409` or queue |
| Prompt failed | Return error, still delete ephemeral session |
| Deletion failed | Return result plus warning and session ID |
| Timeout | Abort run, cleanup, `504` |

## REST

```http
POST /api/execute
Content-Type: application/json
```

Response:

```json
{
  "requestId": "req-123",
  "status": "completed",
  "finishReason": "NORMAL",
  "text": "...",
  "inputTokens": 123,
  "outputTokens": 45,
  "sessionId": null,
  "durationMs": 1234
}
```

## gRPC

```proto
service ExecutionService {
  rpc Execute(ExecutionRequest) returns (ExecutionResponse);
}
```

Same semantics as REST. Authentication via TLS/mTLS.

## Kafka

- Request topic: `qoc.execute.request`
- Response topic: `qoc.execute.response`
- Key: `sessionId` or `requestId`
- Payload: JSON or Avro
- Errors: response with `status=failed` or dead-letter topic

Kafka is at-least-once. Therefore the gateway must be idempotent:

- store `requestId`.
- do not re-execute already completed requests.
- return the result from the idempotency store.

## Audit and observability

- Every run gets a `requestId`.
- Log:
  - agent
  - session
  - finish reason
  - token usage
  - duration
  - errors
- Optionally store results in a separate audit table, since ephemeral
  sessions are deleted.
- Tracing via OpenTelemetry if enabled.

## Security

- REST: existing basic auth.
- gRPC: TLS/mTLS.
- Kafka: SASL/SSL.
- Config override only for authorized clients.
- Validate inline agent definitions:
  - allowed tools
  - allowed MCP servers
  - maximum `steps` (if set)
  - no unchecked system prompts for unauthorized clients

## Risks

- Per-request config increases complexity.
- Too many parallel runs overload the e2-micro instance.
- Ephemeral sessions must be reliably deleted.
- Inline agents must not receive unauthorized tool or MCP access.
- Kafka at-least-once requires idempotency.

## Recommended implementation order

1. Define `ExecutionRequest` and `ExecutionResponse`.
2. Introduce `Engine.runPrompt(..., AgentConfig)` overload.
3. Implement `ExecutionGateway` for REST with existing agents.
4. Ephemeral sessions with guaranteed cleanup.
5. Inline agent definitions.
6. Per-request config override.
7. Idempotency and audit.
8. gRPC adapter.
9. Kafka adapter.

This yields a task instance that can work both stateless and sequentially,
without changing global config state.

---

# Distributed orchestrator/worker architecture

## Goal

A Quarkus orchestrator container deterministically controls multiple worker
containers. The workers execute agent tasks. Orchestrator and workers share a
database for sessions, events, config, and audit.

The orchestrator can adjust agent definitions and produce new config
versions. Workers receive only a config ID and version.

## Basic principle

- **Orchestrator**: receives tasks, resolves config, selects workers,
  dispatches, monitors, aggregates.
- **Worker**: loads config version, executes prompt in a session, writes
  results.
- **Shared PostgreSQL DB**: sessions, events, config, task status, audit.
- **Shared workspace**: all workers see the same files.
- **Agent config versioned in the DB**, not only in `settings.json`.

SQLite is unsuitable for multiple containers. Shared state requires
PostgreSQL.

## Data model

### Agent config

```text
agent_config
  id uuid primary key
  name text not null
  created_at timestamptz not null

agent_config_version
  id uuid primary key
  config_id uuid not null references agent_config(id)
  version int not null
  definition jsonb not null
  created_at timestamptz not null
  created_by text
  unique (config_id, version)
```

The `definition` corresponds to the known agent definition:

```json
{
  "name": "researcher",
  "system": "...",
  "model": "deepseek-v4.1-flash",
  "steps": 20,
  "tools": { "allow": ["read", "websearch", "subagent"], "deny": [] },
  "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
  "mcpServers": ["*"]
}
```

### Execution task

```text
execution_task
  id uuid primary key
  request_id text unique not null
  session_id text references session(id)
  agent_config_version_id uuid references agent_config_version(id)
  prompt text not null
  status text not null
  created_at timestamptz not null
  started_at timestamptz
  finished_at timestamptz
  worker_id uuid
```

Status values:

```text
queued
running
completed
failed
cancelled
```

### Execution result

```text
execution_result
  task_id uuid primary key references execution_task(id)
  finish_reason text
  text text
  input_tokens bigint
  output_tokens bigint
  duration_ms bigint
  error text
  created_at timestamptz not null
```

### Worker registry

```text
worker_instance
  id uuid primary key
  name text
  endpoint text
  status text
  last_heartbeat timestamptz
  capacity int
  active_tasks int
```

## Why config ID instead of full config?

A **config ID plus version** is recommended:

```json
{
  "configId": "cfg_123",
  "configVersion": 3,
  "prompt": "...",
  "sessionId": "ses_..."
}
```

Advantages:

- small messages
- versioned and auditable config
- running tasks stay bound to their version
- orchestrator can produce new versions without redeploying workers
- rollback to older versions possible

Inline definitions can additionally be supported. The orchestrator creates a
temporary config version from them.

## Orchestrator

The orchestrator is deterministic. It makes no content LLM decisions about
routing; it uses rules.

Tasks:

- accept task
- resolve config ID/version
- select or create session
- select or start worker
- dispatch task
- control retry, timeout, and cancel
- aggregate results
- delete ephemeral sessions
- write audit

### Orchestrator tools

- `create_agent_config`
- `update_agent_config`
- `get_agent_config`
- `start_worker`
- `stop_worker`
- `list_workers`
- `submit_task`
- `get_task_status`
- `cancel_task`
- `run_in_session`

### Deterministic rules

Examples:

```text
task.type = "research"    -> Agent "researcher"
task.type = "code-review"  -> Agent "reviewer"
task.priority = "high"     -> Worker pool "fast"
```

Further criteria:

- capability matrix
- worker utilization
- retry policy
- timeout policy
- fallback agent

## Worker

Workers run in mode:

```bash
QOC_ROLE=worker
```

They offer an execution endpoint:

```http
POST /api/execute
```

or consume Kafka messages.

Worker tasks:

- load config version from DB
- load or create session
- execute prompt
- write events and result to DB
- delete ephemeral session
- report health/heartbeat

Workers should have no UI and no global agent management. They are pure
execution instances.

## Shared state

### Database

PostgreSQL is the common source for:

- sessions
- event log
- messages
- inbox
- tool output
- config versions
- task status
- audit

### Workspace

Sessions reference directories such as `/data/workspace`. In a distributed
environment this path must be the same for all workers.

Options:

1. Shared volume / NFS
2. Object storage with local cache
3. Worker-specific workspaces with file transfer

First step: mount a shared volume.

Important: if workers see different files, `read`, `write`, `edit`, `glob`,
and `grep` become inconsistent.

## Session strategy

### A. Sequential with existing session

- Orchestrator passes `sessionId`.
- Worker loads the session from the DB.
- Worker switches agent if needed.
- Worker executes the prompt.
- Session remains preserved.

### B. New ephemeral session

- Worker creates a new session.
- Worker executes the prompt.
- Worker deletes the session in `finally`.
- Result is stored in `execution_result` so audit is preserved.

### C. Parallel subtasks

- Orchestrator starts multiple workers.
- Each worker gets its own session or
- the same session is serialized per worker.

## Config adjustments by the orchestrator

The orchestrator writes new versions instead of overwriting existing ones:

```http
POST /api/agent-configs
POST /api/agent-configs/{id}/versions
```

Example:

- `researcher` v3 is the basis.
- Orchestrator creates `researcher` v4 with adjusted system prompt or tool
  set.
- Task references v4.
- Worker loads v4.

Advantages:

- running tasks remain stable
- auditability
- easy rollback
- no silent changes to old tasks

## Communication

### REST

Good for synchronous, small tasks:

```http
POST /api/execute
```

### gRPC

Good for typed service-to-service communication.

### Kafka

Good for:

- asynchronous tasks
- decoupling
- replay and audit
- scaling

Recommendation:

- Kafka for task dispatch and results
- REST/gRPC for status, config, and control

## Worker lifecycle

First step:

- static worker containers
- health checks
- round-robin or least-busy distribution

Later:

- dynamic worker startup
- Kubernetes jobs/pods
- autoscaling
- priority queues
- worker pools by capability

## Security

- Service-to-service authentication via mTLS or JWT.
- Workers may only read config versions.
- Orchestrator may manage config versions and tasks.
- Tenant isolation in DB and workspace.
- Shell tools in workers only with sandbox/containment.
- Validate agent definitions before storing.
- No secrets in task payloads; config references credentials via IDs.

## Observability

- Distributed tracing via OpenTelemetry.
- Every task has `requestId`, `taskId`, `sessionId`, `workerId`.
- Metrics:
  - task latency
  - worker utilization
  - error rate
  - token usage per agent
  - session lifetime
- Collect logs centrally.

## Risks

- SQLite is unsuitable for multiple workers.
- Shared workspace requires consistent mounts or file transfer.
- Config mutations must not influence running tasks; versioning is mandatory.
- Worker crashes can leave ephemeral sessions behind; retention and recovery
  needed.
- Too many config versions bloat DB and audit.
- Tool execution in workers is security-critical.
- Kafka at-least-once requires idempotency.

## Recommended implementation order

1. Use PostgreSQL as shared DB.
2. Introduce `agent_config` and `agent_config_version`.
3. Use config ID/version in task requests.
4. Build execution gateway transport-independently.
5. Introduce worker mode for QOC containers.
6. Static workers and round-robin dispatch.
7. Session reuse and ephemeral sessions with cleanup.
8. Orchestrator tools for worker and task management.
9. Kafka dispatch and results.
10. Dynamic worker scaling.
11. Full observability.

This yields a scalable architecture where config and sessions are central,
workers execute statelessly, and the orchestrator controls deterministically.