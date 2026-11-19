# Subagent Delegation – Worker Pool, Routing, and Cloud Topologies

Status: Design/decision document (not implemented)
Date: 2026-11-04

This document describes how the orchestrator delegates subtasks to local,
serverless, and server workers via the existing `subagent` tool — including
worker pool, routing, tool surface, MCP proxy variants, and cloud topologies
(GCP/AWS).

## 1. Goal

The orchestrator (Server Mode) should be able to delegate independent
subtasks to a pool of worker runtimes through **a single tool**
(`subagent`):

- **Serverless workers** for parallel, stateless I/O tasks
  (websearch, webfetch, MCP, batch).
- **Server workers** for tasks with a shared, persistent workspace.
- **Local subagents** for workspace-bound tasks in the orchestrator.

The pool handles selection, load balancing, health, retry, failover,
timeouts, and cost/token aggregation.

## 2. Non-goals

- No new central broker service.
- No arbitrary worker URLs supplied by the model (no SSRF).
- No replacement for A2A interoperability with external agents.
- No shared state between serverless workers.
- No dynamic autoscaling of server workers in the MVP.

## 3. Terminology

| Term | Meaning |
|---|---|
| **Worker** | QOC runtime offering `qoc_run_agent` via `/mcp` |
| **Worker Registry** | Server-side configuration of all workers |
| **SubagentRouter** | Selects worker + runner per task |
| **LocalRunner** | Today's in-process subagent |
| **McpWorkerRunner** | Remote worker call via MCP |
| **Capability** | Tag for routing decisions, e.g. `web`, `workspace`, `mcp:jira` |
| **Placement** | `local`, `remote`, or `any` |
| **Leaf Worker** | Worker without `subagent`/`task` tool (no fan-out) |

## 4. Current State

- `subagent` tool: `agent`, `prompt`, `name`, `background`, `tasks[]`
  (`SubagentTool`).
- `tasks[]` is executed **sequentially** (`executeBatch`).
- `SubagentRunner` is a functional interface; the only implementation is
  `Engine.runSubagent` (in-process).
- Depth limit: max. 2 (`Engine.runSubagent`); global semaphore for
  subagent concurrency.
- Background tasks: `BackgroundTasks` with virtual threads, in-process.
- MCP server exposes `/mcp` with `qoc_run_agent` and
  `qoc_run_agent_with_config`.
- Serverless mode loads agents on demand from the server.
- A2A server uses a **fixed** agent (`engine.a2a.server.agent`);
  no agent selection per call.
- A worker registry and router do not yet exist.

## 5. Architecture

```
Orchestrator (Server, Workspace, Plan)
  │
  └─ subagent(agent, prompt, capability?, worker?, tasks[]?)
       │
       └─ SubagentRouter
            ├─ LocalRunner
            │    └─ Engine.runSubagent (in-process)
            │
            └─ WorkerPool (MCP /mcp → qoc_run_agent)
                 ├─ sls-web-1    (serverless, ephemeral, web/MCP)
                 ├─ sls-web-2    (serverless, ephemeral, web/MCP)
                 ├─ srv-shared-1 (server, shared Workspace, editor)
                 └─ srv-shared-2 (server, shared Workspace, reviewer)
```

The pool is a **logical** layer in the orchestrator:

- Serverless workers scale horizontally via the platform.
- Server workers are registered, long-lived endpoints.
- The router knows capabilities, load, health, and limits.

## 6. Transport

**Recommendation: MCP (`POST /mcp` → `qoc_run_agent`) for serverless and
server workers.**

Rationale:

- `qoc_run_agent` selects agent + prompt + mode per call.
- Returns `_meta` with tokens, `sessionId`, `agentCreated`/`agentDeleted`.
- Both worker kinds already speak `/mcp`.
- Uniform auth (OIDC bearer) and error handling.

A2A remains for external agent interoperability. The QOC A2A server uses a
fixed agent and is therefore less suitable for the internal pool with
per-call agent selection.

## 7. Components

### 7.1 WorkerRegistry

Server-side configuration (settings.json or env). **Not** influenced by
the model.

```json
{
  "workers": {
    "sls-web-1": {
      "kind": "mcp",
      "url": "https://sls-web-1.example.com/mcp",
      "auth": "delegated",
      "agents": ["web-researcher"],
      "capabilities": ["web", "mcp:firecrawl"],
      "placement": "remote",
      "maxConcurrency": 4,
      "timeoutMs": 600000,
      "healthPath": "/api/health",
      "healthIntervalSeconds": 30,
      "retry": { "maxAttempts": 3, "backoffMs": [10000, 30000] },
      "circuitBreaker": { "failures": 3, "cooldownSeconds": 60 },
      "egress": { "allowedHosts": ["sls-web-1.example.com"], "tlsRequired": true }
    },
    "srv-shared-1": {
      "kind": "mcp",
      "url": "https://worker-1.example.com/mcp",
      "auth": "delegated",
      "agents": ["editor", "reviewer"],
      "capabilities": ["workspace"],
      "placement": "remote",
      "maxConcurrency": 2,
      "timeoutMs": 600000
    }
  }
}
```

- Secrets are **not** in the registry, but in the credential store or
  env variables.
- `auth`: `delegated` (caller token/token exchange), `service`
  (client credentials), or `none` (local only).
- `workers` must be added to the `KNOWN` key list of `EngineConfig`.

### 7.2 SubagentRouter

Extends `SubagentRunner` into a composite runner:

```java
final class SubagentRouter implements SubagentRunner {
    SubagentResult run(SessionId parent, String agent, String prompt, int depth);
}
```

Responsibilities:

- Selection of the runner (local or remote)
- Load balancing
- Health/failover
- Timeout/retry
- Metadata (worker ID, latency, tokens)

### 7.3 LocalRunner

Existing `Engine.runSubagent`:

- Agent must exist locally and satisfy `isSubagent`.
- Depth limit and semaphore remain.
- Workspace-bound.

### 7.4 McpWorkerRunner

- Builds a JSON-RPC call:
  ```json
  {"jsonrpc":"2.0","id":1,"method":"tools/call",
   "params":{"name":"qoc_run_agent",
             "arguments":{"agent":"web-researcher","prompt":"...",
                          "mode":"ephemeral","sessionMode":"new","retain":false}}}
  ```
- Optionally `qoc_run_agent_with_config` with `agentConfig` (dynamic
  agent config).
- Headers:
  - `Authorization: Bearer <delegated token>`
  - `X-QOC-Depth`, `X-QOC-Trace-Id`, `X-QOC-Parent-Session`
  - `Idempotency-Key`
- Response mapping:
  - `content[].text` → `SubagentResult.text`
  - `_meta.inputTokens/outputTokens` → `TokenCount`
  - `_meta.sessionId` → `childSession`
  - `_meta.warnings` → metadata

### 7.5 Health, Retry, Circuit Breaker

- **Health:** periodic check (`/api/health` or MCP `initialize`/
  `tools/list`); a failing worker is temporarily removed from the pool.
- **Retry:** max. 3 attempts, backoff 10s/30s; only on transport error,
  timeout, or 5xx.
- **Circuit breaker:** after N failures cooldown; then half-open test.
- **Failover:** another worker with the same capability.

## 8. Worker Descriptor

| Field | Type | Meaning |
|---|---|---|
| `kind` | `mcp` (MVP), `a2a` (later) | Transport |
| `url` | string | MCP endpoint |
| `auth` | `delegated`/`service`/`none` | Identity |
| `agents[]` | string[] | Supported agents |
| `capabilities[]` | string[] | Routing tags |
| `placement` | `local`/`remote`/`any` | Preferred location |
| `maxConcurrency` | int | Parallel calls per worker |
| `timeoutMs` | int | Call timeout |
| `healthPath` | string | Health endpoint |
| `retry` | object | Attempts/backoff |
| `circuitBreaker` | object | Failures/cooldown |
| `egress` | object | Host/TLS allow-list |

## 9. `subagent` Tool Interface

Existing fields remain compatible. New optional fields:

```json
{
  "agent": "web-researcher",
  "prompt": "Analyze URL A",
  "worker": "sls-web-1",
  "capability": "web",
  "timeoutMs": 600000,
  "agentConfig": "---\nname: inline\nmode: all\n---\n...",
  "background": false,
  "tasks": [
    {"name": "source-1", "agent": "web-researcher", "prompt": "URL A"},
    {"name": "source-2", "agent": "web-researcher", "prompt": "URL B"}
  ]
}
```

- `worker`: explicit worker ID (takes precedence)
- `capability`: pool selection by capability
- `agentConfig`: worker runs `qoc_run_agent_with_config`
- `tasks[]`: batch, executed in parallel
- `background`: as today, but remote-capable

## 10. Routing

Priority:

1. Explicit `worker`
2. `capability` + agent name
3. Agent name → worker mapping (`agents[]`)
4. `placement`:
   - `workspace` → local or server worker with shared workspace
   - `web`/`mcp` → serverless pool
5. Load balancing:
   - least-in-flight
   - round-robin
   - random
6. Health/circuit breaker: skip unhealthy workers
7. Fallback: local, if the agent exists locally and `placement=any`

The router accepts **only registered worker IDs**. The model cannot determine
URLs or hosts.

## 11. Concurrency

- `tasks[]` is executed in parallel.
- Limits:
  - global pool limit
  - `maxConcurrency` per worker
  - existing subagent semaphore for local runners
- `background=true`: virtual thread waits on the remote call; `task` tool
  polls status.
- Partial results: batch returns `ok|failed` per task.
- Timeouts per task; no unbounded runs.

## 12. Error Handling, Idempotency, Depth

- Retry only on transport/5xx/timeout, not on business agent errors.
- Idempotency key per task to the worker; worker deduplicates.
- Depth:
  - `X-QOC-Depth` starts at 0 in the orchestrator.
  - Remote worker rejects depth ≥ `maxDepth`.
  - Leaf workers have `subagent`/`task` deny.
- Loop protection additionally via `X-QOC-Trace-Id` and `X-QOC-Parent-Session`.
- Errors are returned as `ToolOutcome.Failed` with `workerId`, `taskName`,
  and `retryable`.

## 13. Identity, Security, Audit

- **User-triggered calls:** forward the caller token or exchange it via
  RFC 8693 to the worker audience (`act` claim).
- **System tasks:** service identity with narrow scope.
- **Scopes:** `worker:run`, `agent:read`, `mcp:run`; workers have no
  `agent:write`.
- **Egress:** allow-list for worker hosts, TLS requirement optional.
- **Audit:** per task `workerId`, `agent`, `principal`, `act`, tokens,
  latency, result status.
- **No secrets in the registry.**

## 14. Result Contract

Extend `SubagentResult`:

```java
record SubagentResult(
    String text,
    TokenCount usage,
    SessionId childSession,
    String workerId,
    String workerKind,      // local | mcp | a2a
    long latencyMs,
    Map<String, Object> meta
)
```

Tool result per task:

```json
{
  "name": "source-1",
  "workerId": "sls-web-1",
  "workerKind": "mcp",
  "latencyMs": 4210,
  "tokens": { "input": 1200, "output": 300 },
  "text": "...",
  "warnings": []
}
```

## 15. Configuration

### settings.json

```json
{
  "workers": {
    "sls-web-1": { "kind": "mcp", "url": "http://host.docker.internal:8072/mcp",
                   "auth": "delegated", "agents": ["web-researcher"],
                   "capabilities": ["web"], "placement": "remote",
                   "maxConcurrency": 4, "timeoutMs": 600000 },
    "srv-shared-1": { "kind": "mcp", "url": "http://worker-1:8080/mcp",
                      "auth": "delegated", "agents": ["editor"],
                      "capabilities": ["workspace"], "placement": "remote",
                      "maxConcurrency": 2, "timeoutMs": 600000 }
  }
}
```

### Environment variables (overrides)

```env
ENGINE_WORKERS_CONFIG=/data/home/.config/opencode/workers.json
ENGINE_WORKER_MAX_CONCURRENCY=8
ENGINE_WORKER_RETRY_MAX_ATTEMPTS=3
ENGINE_WORKER_RETRY_BACKOFF_MS=10000,30000
ENGINE_WORKER_HEALTH_INTERVAL_SECONDS=30
ENGINE_WORKER_CIRCUIT_FAILURES=3
ENGINE_WORKER_CIRCUIT_COOLDOWN_SECONDS=60
```

## 16. Deployment Topologies

### Local (Docker + Keycloak)

- Orchestrator: `qoc-server` on 8073.
- Serverless worker: `qoc-minimal` on 8072.
- Registry entry:
  `http://host.docker.internal:8072/mcp`.
- Local concurrency: `ENGINE_MAX_CONCURRENT_PROMPTS=4` in serverless.

### VM + Serverless (GCP/AWS)

- Orchestrator/control plane on VM.
- Serverless workers: Lambda Function URL / Cloud Run URL.
- Server workers: further VMs with shared workspace.
- Registry entries with HTTPS, OIDC audience, egress allow-list.

### Server workers only

- Multiple QOC instances with a shared workspace mount (read-only
  agent config).
- Registry entries via internal URL.
- Exclusive agent authoring by editor/orchestrator.

## 17. Observability

- Metrics per worker:
  - in-flight
  - latency (p50/p95)
  - error rate
  - retries
  - tokens/cost
- Tracing: forward `traceparent` to the worker; span per call.
- Audit log for every worker call.
- Health status in the `/api/worker` endpoint (new) or in `/api/config`.

## 18. Implementation Plan

> The cloud-specific rollout plan (GCP pool + AWS dedicated tool) is in
> section 22.

### Phase 1 – Registry + explicit routing

1. Parse `workers` block in `EngineConfig` (incl. `KNOWN` keys).
2. `WorkerRegistry` with descriptors and validation.
3. `McpWorkerRunner` (JSON-RPC `qoc_run_agent`).
4. `SubagentRouter` with `worker` field.
5. Extend `subagent` schema with `worker`/`capability`/`timeoutMs`.

### Phase 2 – Pool behavior

1. Capability and agent mapping routing.
2. Least-in-flight + round-robin.
3. Health checks, retry, circuit breaker, failover.
4. Parallelize batch (bounded executor).
5. Remote-capable background tasks.

### Phase 3 – Comfort and hardening

1. `agentConfig` (dynamic agent config) in the subagent tool.
2. Token exchange (RFC 8693) instead of raw forwarding.
3. Metrics, audit, `/api/worker` status.
4. Optional: dynamic discovery (MCP `tools/list`, A2A agent cards).
5. Optional: A2A transport for external workers.

## 19. Test Plan

- Registry parsing and validation.
- Routing: explicit, capability, agent mapping, fallback.
- Load balancing: least-in-flight, round-robin.
- Retry: 3 attempts, backoff, no retry on 4xx.
- Circuit breaker: failure and recovery.
- Failover to second worker.
- Parallel batch: 3 tasks, 2 workers, correct assignment.
- Depth limit and loop protection.
- Auth: caller token/exchange, 401/403 cases.
- Egress allow-list (no SSRF).
- Result mapping (`_meta`, tokens, latency).
- Local integration: server + serverless via `host.docker.internal`.
- JaCoCo ≥ 80 %.

## 20. Open Decisions

1. Transport: MCP for all (recommended) or server workers via A2A?
2. Registry: static (MVP) or dynamic (health/agent-card discovery)?
3. Auth: forward caller token or always token exchange?
4. Dynamic agent configs: allow `agentConfig` (recommended) or only
   named agents?
5. Placement: agent config, router policy, or caller decides?
6. Async: synchronous MCP calls in background thread (MVP) or real
   remote job API?
7. Pool limits: global limit and per-worker limit — where configured?
8. Should server workers report their own `ENGINE_ROLE=worker` capabilities?

## 21. Cloud Topologies and Tool Surface (GCP vs. AWS)

### 21.1 Decision

The tool surface follows the trust and cloud boundary, not the number
of agents:

| Environment | Tool surface | Rationale |
|---|---|---|
| Local + GCP Cloud Run | **generic `subagent`** with `environment=gcp` | same GCP/OIDC/egress domain as the orchestrator VM; config-driven, scalable |
| AWS Lambda | **dedicated `aws_worker` tool** | different cloud, different auth, different limits, different policy |
| Shared-workspace workers (optional) | own `delegate_workspace` | write/approval policy as its own boundary |
| External A2A agents (optional) | `delegate_external` | different trust boundary/transport |

**Do not** introduce: one tool per agent, per Cloud Run instance, or per
MCP server.

### 21.2 GCP: generic worker pool

The orchestrator runs on the GCP VM, the workers on Cloud Run. Both share:

- the same OIDC tenant (Auth0/Keycloak),
- the same egress/network domain,
- the same observability (GCP Logging/Monitoring),
- the same secret management.

Therefore a generic `subagent` tool suffices:

```json
{
  "agent": "web-researcher",
  "environment": "gcp",
  "capability": "web",
  "prompt": "Analyze URL A",
  "tasks": [
    {"name": "source-1", "agent": "web-researcher", "prompt": "URL A"},
    {"name": "source-2", "agent": "web-researcher", "prompt": "URL B"}
  ]
}
```

Registry:

```json
{
  "workers": {
    "gcp-web-1": {
      "kind": "mcp",
      "url": "https://qoc-worker-1-xyz.a.run.app/mcp",
      "auth": "delegated",
      "environment": "gcp",
      "agents": ["web-researcher"],
      "capabilities": ["web", "mcp:firecrawl"],
      "placement": "remote",
      "maxConcurrency": 4,
      "timeoutMs": 600000,
      "egress": { "allowedHosts": ["qoc-worker-1-xyz.a.run.app"], "tlsRequired": true }
    },
    "gcp-web-2": {
      "kind": "mcp",
      "url": "https://qoc-worker-2-xyz.a.run.app/mcp",
      "auth": "delegated",
      "environment": "gcp",
      "agents": ["web-researcher"],
      "capabilities": ["web", "mcp:firecrawl"],
      "placement": "remote",
      "maxConcurrency": 4,
      "timeoutMs": 600000
    }
  }
}
```

Benefits:

- New Cloud Run workers are pure config entries.
- The router handles load balancing, health, retry, failover.
- No extra tool in the prompt; the agent catalog guides the model.
- Uniform metrics/audit across all GCP workers.

### 21.3 AWS: dedicated tool

The AWS Lambda worker is a separate trust boundary. A dedicated tool
encapsulates:

- a fixed target worker (no model-driven routing),
- cross-cloud auth,
- Lambda-specific limits,
- its own policy (no workspace, no shell, only web/MCP),
- CloudWatch trace correlation.

Tool schema:

```json
{
  "name": "aws_worker",
  "description": "Run a stateless task on the AWS Lambda worker (cross-cloud, no shared workspace).",
  "input": {
    "prompt": "string",
    "agent": "string (optional, enum from allow-list)",
    "structuredOutput": "object (optional JSON Schema)",
    "timeoutMs": "integer (optional, max 900000)"
  }
}
```

Internals:

- fixed worker `aws-lambda-1`
- `McpWorkerRunner` → `qoc_run_agent` or `qoc_run_agent_with_config`
- `mode=ephemeral`, `sessionMode=new`, `retain=false`
- Auth: token exchange to `aud=qoc-aws-worker` or service identity
- Result: text + `structuredContent` + `_meta` (tokens, latency,
  `X-Amzn-Trace-Id`)

Registry:

```json
{
  "workers": {
    "aws-lambda-1": {
      "kind": "mcp",
      "url": "https://abc123.lambda-url.eu-central-1.on.aws/mcp",
      "auth": "exchange",
      "environment": "aws",
      "agents": ["aws-worker"],
      "capabilities": ["batch", "web"],
      "placement": "remote",
      "maxConcurrency": 8,
      "timeoutMs": 900000,
      "egress": { "allowedHosts": ["abc123.lambda-url.eu-central-1.on.aws"], "tlsRequired": true }
    }
  },
  "tools": {
    "aws_worker": {
      "worker": "aws-lambda-1",
      "description": "Run a stateless task on the AWS Lambda worker."
    }
  }
}
```

### 21.4 Tool landscape

| Tool | Target | Purpose |
|---|---|---|
| `subagent` | local + GCP Cloud Run pool | generic delegation, batch, dynamic agents |
| `aws_worker` | AWS Lambda worker | dedicated cross-cloud tasks |
| `delegate_workspace` (optional) | shared-workspace workers | write/approval policy separated |
| `delegate_external` (optional) | A2A/external agents | interoperability |

All tools use the same `SubagentRouter`/`McpWorkerRunner` internally. The
dedicated tools are thin facades with a fixed target and a narrower schema.

### 21.5 Cross-cloud auth, transport, and limits

| Topic | Recommendation |
|---|---|
| Auth | Prefer the same Auth0 tenant + RFC 8693 token exchange to `aud=qoc-aws-worker`. Alternatively service token. |
| Transport | HTTP Function URL with MCP (`/mcp`); with IAM auth a SigV4 adapter before the MCP call. |
| Timeout | Lambda max. 15 min; QOC worker `turn.max.wall-minutes=10`; tool timeout accordingly. |
| Payload | Lambda sync max. 6 MB; no workspace files transferred, text/JSON only. |
| Cold start | Optional provisioned concurrency; tool may use `background=true`. |
| Egress | GCP VM → AWS Function URL via public internet or PrivateLink/VPC endpoint; allow-list. |
| Observability | Return `X-Amzn-Trace-Id` and CloudWatch log reference in `_meta`. |
| Secrets | AWS Secrets Manager / GCP Secret Manager, not in the registry. |
| Function URL auth | `auth-type NONE` only with OIDC in the QOC process; better IAM auth or WAF in front. |

### 21.6 When a GCP worker also gets a dedicated tool

Not for every agent. Only if at least two points apply:

1. different input/output contract (e.g. patch review, structured JSON),
2. different security policy (workspace writes, approval, egress),
3. different environment/topology (shared workspace instead of ephemeral),
4. own audit/compliance path,
5. frequent use with high misuse risk.

Otherwise it remains an agent behind the generic `subagent` tool.

### 21.7 Security and observability

- The tool name and description tell the model clearly which environment is
  meant (`aws_worker` = AWS, stateless, no workspace).
- The model cannot set worker URLs; only registered IDs/aliases.
- Audit per call: `workerId`, `environment`, `agent`, `principal`, `act`,
  tokens, latency, cloud trace ID.
- Metrics separated by `environment` (`gcp`, `aws`, `local`), so that
  cross-cloud latency and error rates are visible.

### 21.8 MCP proxy variant: multiple tool profiles per worker

An MCP server can offer multiple tools in `tools/list`. QOC registers each
tool namespaced as `<server>_<tool>` (`McpTool`). The agent controls access
via `mcpServers`, `tools.allow/deny`, and `permissions`.

#### Three variants

| Variant | Setup | Tool names in QOC | Additional service |
|---|---|---|---|
| **A: Direct** | QOC → Lambda `/mcp` | `aws_qoc_run_agent`, `aws_qoc_run_agent_with_config` | no |
| **B: MCP proxy** | QOC → MCP proxy → AWS | `aws_worker`, `aws_task_status`, … | yes |
| **C: Proxy + QOC wrapper** | QOC ToolDefinition → MCP proxy | stable `aws_worker` | yes |

- **A** is the fastest path: the QOC Lambda worker already exposes `/mcp`.
  The agent sees the generic worker tools.
- **B** encapsulates AWS auth, Lambda invoke, limits, and trace IDs in a
  separate MCP server. The proxy defines the tool schemas.
- **C** wraps the proxy tool in a QOC `ToolDefinition` with a stable name,
  a narrow schema, and its own policy. The proxy remains replaceable.

#### Multiple tool profiles on one server

A worker or proxy can offer both generic and dedicated tools
simultaneously. Recommended names (server `aws`):

| Tool on server | Effective name | Schema | Purpose |
|---|---|---|---|
| `worker` | `aws_worker` | `prompt`, `structuredOutput`, `timeoutMs` | dedicated, fixed agent, fixed policy |
| `agent` | `aws_agent` | `agent`, `prompt`, `structuredOutput`, `timeoutMs` | generic, agent selectable |
| `inline` | `aws_inline` | `agentConfig`, `prompt`, `maxSteps`, `timeoutMs` | dynamic agent config |

Naming trap: QOC prefixes with the **server name**. Server `aws` + tool
`worker` yields `aws_worker`. Server `aws` + tool `aws_worker` yields
`aws_aws_worker`.

Example `tools/list`:

```json
[
  {
    "name": "worker",
    "description": "Run a task on the dedicated AWS worker (fixed agent, no agent selection).",
    "inputSchema": {
      "type": "object",
      "properties": {
        "prompt": { "type": "string" },
        "structuredOutput": { "type": "object" },
        "timeoutMs": { "type": "integer" }
      },
      "required": ["prompt"]
    }
  },
  {
    "name": "agent",
    "description": "Run an allowed QOC agent on the AWS worker.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "agent": { "type": "string" },
        "prompt": { "type": "string" },
        "structuredOutput": { "type": "object" },
        "timeoutMs": { "type": "integer" }
      },
      "required": ["agent", "prompt"]
    }
  }
]
```

#### Access via agent config

Three levels apply:

1. **`mcpServers`** – namespace grant; without `"aws"` all AWS tools are
   removed.
2. **`tools.allow` / `tools.deny`** – concrete tool names.
3. **`permissions`** – finer rules per action/resource.

```json
{
  "name": "aws-specialist",
  "mcpServers": ["aws"],
  "tools": {
    "allow": ["aws_worker"],
    "deny": []
  },
  "permissions": [
    { "action": "aws_worker", "resource": "*", "effect": "allow" }
  ]
}
```

Access matrix:

| Agent | `mcpServers` | `tools.allow` | Visible AWS tools |
|---|---|---|---|
| `aws-specialist` | `["aws"]` | `["aws_worker"]` | only dedicated worker tool |
| `aws-generalist` | `["aws"]` | `["aws_agent"]` | generic agent tool |
| `aws-power` | `["aws"]` | `["aws_worker", "aws_agent"]` | both |
| `local-only` | `[]` | `["*"]` | no AWS tools |

The namespace filter also applies with `tools.allow: ["*"]`: without
`mcpServers: ["aws"]` the tools remain invisible.

#### MCP configuration in QOC

```json
{
  "mcp": {
    "aws": {
      "type": "remote",
      "url": "https://mcp-aws.example.com/mcp",
      "headers": { "Authorization": "Bearer ${AWS_MCP_TOKEN}" }
    }
  }
}
```

- `${AWS_MCP_TOKEN}` comes from env/secret manager, not from
  `settings.json`.
- `tools/list` is read at startup; if the server is unreachable, the
  tools are missing.
- For remote MCP servers an egress allow-list like the one for A2A
  (`allowedHosts`/`tlsRequired`) is currently missing; add it for AWS
  URLs.

#### Per-user identity (important limitation)

QOC's MCP client currently sends static `headers` and optionally an active
credential-store token. It does **not** automatically forward the bearer
token of the calling user. The AWS proxy therefore sees a service identity.

Options:

1. Token exchange (RFC 8693) before the MCP call, `aud=aws-mcp`,
   `scope=worker:run`, `act` claim.
2. Additional headers `X-QOC-User`/`X-QOC-Act` (only with a trust
   relationship).
3. For now accept service identity and audit the QOC context.

#### When which variant?

- **A** for MVP and quick commissioning without an additional service.
- **B** when AWS auth/limits/trace ID should be developed independently of
  QOC.
- **C** when the orchestrator needs a stable, narrow `aws_worker` tool with
  its own policy/audit.

#### Implementation note

- **A:** The Lambda worker can extend
  `McpServerResource.toolsList()`/`toolsCall()` by tool profiles (e.g.
  `worker`, `agent`, `inline`), controlled via `ENGINE_MCP_TOOL_PROFILES`.
- **B/C:** The external MCP proxy defines the tools; on the QOC side it is
  pure config (B) or a thin wrapper (C).
- Agent config access remains the same in all variants.

---

## 22. Implementation Plan GCP/AWS

### Phase 1 – Common infrastructure

1. Parse `workers` block in `EngineConfig` (incl. `KNOWN` keys).
2. `WorkerRegistry` with descriptors (`environment`, `auth`,
   `capabilities`, limits, egress).
3. `McpWorkerRunner` (JSON-RPC `qoc_run_agent`).
4. `SubagentRouter` with `worker`, `environment`, and `capability`.
5. Extend `subagent` schema (`environment`, `worker`, `capability`,
   `timeoutMs`, `agentConfig`).
6. Parallelize batch (bounded executor).

### Phase 2 – GCP pool

1. Cloud Run workers as registry entries (`environment=gcp`).
2. Routing: `environment=gcp` + `capability` + least-in-flight.
3. Health checks against Cloud Run (`/api/health`).
4. Delegated token (caller token or exchange) to Cloud Run.
5. Egress allow-list for Cloud Run hosts.
6. Local integration test: orchestrator on 8073, worker on 8072.

### Phase 3 – AWS dedicated tool

**Variant A – MCP tool profiles in the Lambda worker:**

1. Extend `McpServerResource.toolsList()`/`toolsCall()` by tool profiles
   (`worker`, `agent`, `inline`), controlled via
   `ENGINE_MCP_TOOL_PROFILES`.
2. `worker`: narrow schema (`prompt`, `structuredOutput`, `timeoutMs`),
   fixed agent, fixed policy.
3. `agent`: generic (`agent`, `prompt`, `structuredOutput`, `timeoutMs`).
4. `inline`: `agentConfig` + `prompt`, only for explicitly allowed agents.
5. QOC side: configure remote MCP server `aws`; agent config controls
   access via `mcpServers`, `tools.allow/deny`, `permissions`.
6. Tool names: server `aws` + tool `worker` → `aws_worker` (no double
   prefix).

**Variant C – QOC wrapper (optional):**

1. Generate `aws_worker` tool from the registry (fixed worker alias).
2. Narrow schema: `prompt`, optionally `structuredOutput`, `timeoutMs`.
3. Auth adapter: token exchange to `aud=qoc-aws-worker` or service token.

**Common:**

1. Lambda limits: timeout ≤ 900s, payload ≤ 6 MB, no workspace files.
2. Extend `_meta` by `environment=aws`, `workerId`, `latencyMs`,
   `X-Amzn-Trace-Id`.
3. Error mapping: cold-start timeout, 413 payload too large, 401/403,
   Lambda 5xx.
4. Optional: `background=true` for long Lambda runs.

### Phase 4 – Hardening

1. Retry (3 attempts, backoff 10s/30s) and circuit breaker per worker.
2. Failover within the GCP pool; AWS tool with clear error message.
3. Metrics/audit per `environment`.
4. `/api/worker` status endpoint (registry, health, in-flight).
5. Optional: provisioned concurrency for AWS; WAF/IAM in front of the
   function URL.
6. Optional: dynamic discovery (Cloud Run service discovery, AWS
   function URL registry).

### Tests

- GCP: 3 parallel `tasks[]` on 2 Cloud Run workers, correct assignment.
- GCP: failover when one worker is unhealthy.
- AWS: `aws_worker` reaches only the configured Lambda worker.
- AWS: tool profiles `aws_worker`/`aws_agent`/`aws_inline` are namespaced
  correctly and visible only per agent config.
- AWS: `mcpServers` filter applies even with `tools.allow: ["*"]`.
- AWS: auth errors, timeout, 413, cold-start retry.
- Cross-cloud: token exchange/audience verification.
- Security: no free URL, egress allow-list applies.
- Result: `_meta` contains `environment`, `workerId`, latency, cloud trace.
- JaCoCo ≥ 80 %.

### Acceptance Criteria

- `subagent(environment=gcp, capability=web, tasks=[...])` runs in parallel
  on Cloud Run and returns a result per task.
- `aws_worker(prompt=...)` runs on the AWS Lambda worker, returns text/
  structured result and an AWS trace reference.
- A new GCP worker is usable without code deploy, only via registry entry.
- A worker failure leads to retry/failover or a clear error message.
- AWS worker has no workspace access and no shell.

### Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Cross-cloud latency | Timeouts, `background=true`, provisioned concurrency, PrivateLink |
| Lambda cold start | Provisioned concurrency or warm minimum instance |
| Two IdPs/audiences | Token exchange centrally in the auth adapter, not in the tool |
| Data residency | AWS worker only stateless text/JSON, no workspace data |
| Public function URL | IAM auth/WAF, OIDC in the QOC process, rate limit |
| Payload limit | Structured output instead of files, 6-MB check before the call |

### Open Decisions (Cloud)

1. AWS auth: token exchange (recommended) or service token?
2. Function URL: `auth-type NONE` + OIDC or IAM/SigV4?
3. AWS tool: fixed target or multiple AWS workers with alias?
4. GCP worker: Cloud Run directly or via API gateway?
5. PrivateLink/VPC endpoint needed or public internet with allow-list?
6. Should AWS tasks default to `background=true`?
7. AWS tool profiles: only `worker` (dedicated), `agent` (generic), or both?
8. Should `inline` (`agentConfig`) be exposed at all — and for which
   agents?
9. MCP proxy (variant B) or tool profiles directly in the Lambda worker
   (variant A)?

## 23. References

- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentRunner.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` (`runSubagent`)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/McpServerResource.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/mcp/McpDiscoveryClient.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/A2AConfig.java`
- `docs/guides/deployment-modes.md`
