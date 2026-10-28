# A2A – Agent-to-Agent Protocol: Client and Server Configuration

> **Subject:** How to configure QOC as an A2A **client** (external agents as
> tools) and as an A2A **server** (exposing QOC agents over JSON-RPC).
>
> **Status:** 2026-10-28 · Implementation guide (QOC-specific)
>
> **See also:** [`docs/analysis/a2a.md`](../analysis/a2a.md) (analysis and
> phases), [`docs/Security.md`](../Security.md) §4.8a (security model),
> [`docs/spec/tools/04-mcp.md`](../spec/tools/04-mcp.md) (MCP, the
> complementary protocol).

## Contents

1. [Overview](#1-overview)
2. [Client: external A2A agents as tools](#2-client-external-a2a-agents-as-tools)
   - [Minimal configuration](#21-minimal-configuration)
   - [Endpoint format](#22-endpoint-format)
   - [Authentication](#23-authentication)
   - [Egress guard](#24-egress-guard)
   - [Discovery and cache](#25-discovery-and-cache)
   - [Tool usage](#26-tool-usage)
3. [Server: exposing QOC as an A2A agent](#3-server-exposing-qoc-as-an-a2a-agent)
   - [Enabling the server](#31-enabling-the-server)
   - [Configuration reference](#32-configuration-reference)
   - [Agent card](#33-agent-card)
   - [Authentication](#34-authentication)
   - [Task lifecycle and sessions](#35-task-lifecycle-and-sessions)
4. [Combined example: client + server](#4-combined-example-client--server)
5. [Streaming (SSE)](#5-streaming-sse)
6. [Troubleshooting](#6-troubleshooting)

---

## 1. Overview

QOC supports the [A2A protocol](https://a2a-protocol.org/) in both directions:

- **Client**: QOC calls remote A2A agents as if they were tools. Each
  configured endpoint becomes one tool in the tool registry and goes through
  the same permission, guardrail and audit pipeline as built-in tools.
- **Server**: QOC exposes its own agents (from `AgentCatalogue`) as an A2A
  agent over JSON-RPC. The server is **opt-in** and disabled by default.

Transport is **JSON-RPC only** (gRPC is not enabled). The SDK version is
`org.a2aproject.sdk:1.4.0.Final`.

---

## 2. Client: external A2A agents as tools

### 2.1 Minimal configuration

Comma-separated URLs (simplest form):

```properties
# application.properties or env var ENGINE_A2A_URLS
engine.a2a.urls=http://localhost:9999,http://agent-b:8080
```

Or via `settings.json` (project or global):

```json
{
  "a2a": {
    "urls": "http://localhost:9999,http://agent-b:8080"
  }
}
```

> **Note:** QOC reads A2A client config from MicroProfile Config
> (`engine.a2a.urls` / `engine.a2a.endpoints`), which can be set via
> environment variables `ENGINE_A2A_URLS` / `ENGINE_A2A_ENDPOINTS`.

### 2.2 Endpoint format

For per-endpoint control (id, tool name, enabled, auth, egress), use the
semicolon/pipe format:

```properties
engine.a2a.endpoints=id=weather|url=http://weather-agent:8080|tool=a2a_weather|enabled=true;id=research|url=http://research-agent:8080|enabled=true
```

Fields (all optional except `url`):

| Field | Default | Description |
|---|---|---|
| `url` | — | Base URL of the remote A2A agent (required) |
| `id` | `a2aN` | Stable endpoint id |
| `tool` / `toolName` | `a2a_<card name>` | QOC tool name |
| `enabled` | `true` | Set `false` to disable without removing |
| `auth` | `none` | `none`, `bearer:<token>`, `basic:<user>:<password>`, `header:K=V` |
| `headers` | (empty) | Comma-separated `Key:Value` pairs |
| `allowedHosts` | (global) | Comma-separated host allowlist |
| `allowedIps` | (global) | Comma-separated IP/CIDR allowlist |
| `tlsRequired` | (global) | `true`/`false` |

### 2.3 Authentication

Protect remote agents with bearer tokens or basic auth:

```properties
# Bearer token
engine.a2a.endpoints=id=secure|url=https://agent.example.com|auth=bearer:sk-abc123

# Basic auth
engine.a2a.endpoints=id=secure|url=https://agent.example.com|auth=basic:alice:secret

# Custom headers
engine.a2a.endpoints=id=secure|url=https://agent.example.com|headers=X-API-Key:abc,X-Tenant:prod
```

Headers are applied to **both** the AgentCard fetch (`/.well-known/agent-card.json`)
and all JSON-RPC calls. They are part of the cache key, so endpoints with
different credentials do not share a cached card.

### 2.4 Egress guard

Optional host/IP allowlists and TLS enforcement (default-open):

```properties
# Per-endpoint
engine.a2a.endpoints=id=x|url=http://agent:8080|allowedHosts=agent.example.com|allowedIps=10.0.0.0/8|tlsRequired=true

# Global defaults (apply to all endpoints without explicit values)
engine.a2a.egress.allowedHosts=example.com,api.example.com
engine.a2a.egress.allowedIps=10.0.0.0/8,192.168.1.5
engine.a2a.egress.tlsRequired=false
```

Blocked endpoints are skipped at tool registration (logged as warning) and
reported as `status=error` in the discovery endpoint.

### 2.5 Discovery and cache

QOC provides a discovery API for configured endpoints:

```bash
# List all configured agents with card metadata and status
curl -u opencode:secret http://localhost:8080/api/a2a/agents

# Force refresh (invalidate cache)
curl -u opencode:secret -X POST http://localhost:8080/api/a2a/agents/refresh
curl -u opencode:secret "http://localhost:8080/api/a2a/agents?refresh=true"
```

Response shape:

```json
{
  "data": [
    {
      "id": "weather",
      "url": "http://weather-agent:8080",
      "tool": "a2a_weather",
      "enabled": true,
      "status": "ok",
      "name": "Weather Agent",
      "description": "...",
      "version": "1.0.0",
      "protocolVersion": "1.0",
      "skills": [{"id": "forecast", "name": "Forecast", "description": "..."}],
      "fetchedAt": "2026-10-28T10:00:00Z",
      "cached": true,
      "error": null
    }
  ]
}
```

Status values: `ok` (card resolved), `error` (fetch failed), `disabled`
(configured but `enabled=false`).

**Cache:** AgentCards are cached with a TTL (`engine.a2a.cache.ttl-seconds`,
default 300, `0` disables, max 86400). The cache is shared between tool
registration and discovery. Failed fetches are never cached.

### 2.6 Tool usage

Each configured endpoint becomes a tool named `a2a_<id>` (or the configured
`tool` name). The tool accepts:

```json
{"text": "What's the weather in Berlin?", "skill": "forecast"}
```

- `text` (or `message.text`): the prompt sent to the remote agent.
- `skill`: optional hint; prepended to the message as `[skill]`.

The remote agent's text/data/file parts are mapped into the tool result.
Remote agent responses are **untrusted tool output** and pass through the
normal guardrail chain.

The web UI has an **A2A** button that opens a read-only overview of all
configured agents (status, skills, cache state, refresh).

---

## 3. Server: exposing QOC as an A2A agent

### 3.1 Enabling the server

The server is **disabled by default**. Enable it via:

```properties
engine.a2a.server.enabled=true
```

Or via environment variable: `ENGINE_A2A_SERVER_ENABLED=true`.

When disabled, `/.well-known/agent-card.json`, tenant cards and `POST /`
return `404`.

### 3.2 Configuration reference

| Property | Env var | Default | Description |
|---|---|---|---|
| `engine.a2a.server.enabled` | `ENGINE_A2A_SERVER_ENABLED` | `false` | Enable the A2A server |
| `engine.a2a.server.name` | `ENGINE_A2A_SERVER_NAME` | `QOC Agent` | Agent name in the card |
| `engine.a2a.server.description` | `ENGINE_A2A_SERVER_DESCRIPTION` | `QOC AI Agent Runtime` | Agent description |
| `engine.a2a.server.version` | `ENGINE_A2A_SERVER_VERSION` | `1.0.0` | Agent version |
| `engine.a2a.server.base-url` | `ENGINE_A2A_SERVER_BASE_URL` | `http://localhost:8080` | Public URL published in the card |
| `engine.a2a.server.agent` | `ENGINE_A2A_SERVER_AGENT` | `none` | QOC agent to expose (`none` = primary) |

**Important:** `base-url` must be the **externally reachable** URL. It is
published in the agent card and used by clients for JSON-RPC calls.

### 3.3 Agent card

The agent card is published at:

```
GET /.well-known/agent-card.json
```

It contains name, description, version, capabilities (streaming), skills (one
per visible QOC agent) and supported interfaces (JSON-RPC 1.0 and 0.3). No
secrets, tokens or internal paths.

The card advertises `streaming=true`. The server supports both `message/send`
(blocking) and `message/stream` (SSE streaming).

### 3.4 Authentication

The A2A transport uses Vert.x routes, not JAX-RS. `A2AAuthenticationMechanism`
supplies a Quarkus identity:

| Mode | How it works |
|---|---|
| `none` | No authentication (local/single-user mode) |
| `basic` | HTTP Basic against `engine.auth.password` |
| `proxy` | Trusted-proxy headers (`X-Forwarded-User`, etc.) from configured CIDRs |
| `oidc` | Quarkus OIDC extension performs authentication |

Set via `ENGINE_SECURITY_MODE` or `security.mode` in `settings.json`.

**Authorization:** `A2ATaskAuthorizationProvider` is fail-closed. Task
reads/writes are restricted to the creating principal. `none` mode is
intentionally single-user.

### 3.5 Task lifecycle and sessions

- Each A2A task (or context) maps to its own QOC session via
  `A2ASessionRegistry` (LRU-bounded, 1000 contexts).
- The session runs through the regular permission, guardrail and audit pipeline.
- Blocking mode (`message/send`): returns a final task with artifacts.
- Streaming mode (`message/stream`): emits incremental artifact chunks via SSE,
  then a status update (completed).
- Task states: `TASK_STATE_COMPLETED`, `TASK_STATE_FAILED`, `TASK_STATE_CANCELED`,
  etc.

---

## 4. Combined example: client + server

Run two QOC instances where A calls B:

**Instance A (client + server):**

```properties
# A2A server
engine.a2a.server.enabled=true
engine.a2a.server.base-url=http://qoc-a:8080

# A2A client (pointing at B)
engine.a2a.endpoints=id=agent-b|url=http://qoc-b:8080|tool=a2a_agent_b|enabled=true

# Auth
engine.security.mode=basic
engine.auth.password=interop-secret
```

**Instance B (server only):**

```properties
engine.a2a.server.enabled=true
engine.a2a.server.name=Agent B
engine.a2a.server.base-url=http://qoc-b:8080

engine.security.mode=basic
engine.auth.password=interop-secret
```

**Test the round-trip:**

```bash
# Fetch B's card from A's perspective
curl -u opencode:interop-secret http://qoc-a:8080/api/a2a/agents

# Send a message to B via A2A JSON-RPC
curl -u opencode:interop-secret -X POST http://qoc-b:8080/ \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"role":"ROLE_USER","parts":[{"text":"Hallo"}],"messageId":"m1"}}}'

# Stream a response
curl -N -u opencode:interop-secret -X POST http://qoc-b:8080/ \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{"jsonrpc":"2.0","id":"2","method":"SendStreamingMessage","params":{"message":{"role":"ROLE_USER","parts":[{"text":"Hallo"}],"messageId":"m2"}}}'
```

---

## 5. Streaming (SSE)

The server supports `message/stream` (SSE). The client (A2A SDK) can use
streaming mode by setting `ClientConfig.setStreaming(true)` and ensuring the
remote agent card advertises `streaming=true`.

QOC's server streams incremental text deltas (derived from the LLM's
`TextDelta` events) as artifact chunks, followed by a status update
(`TASK_STATE_COMPLETED`).

> **Note:** QOC's `Engine.prompt` is internally blocking; the streaming is
> achieved by forwarding `ModelEvent.TextDelta` events published via the
> `EventBus` to the A2A emitter. True token-level streaming on the Engine
> level is not required — the chunks are already granular enough for
> real-time display.

---

## 6. Troubleshooting

| Problem | Cause / Fix |
|---|---|
| `404` on `/.well-known/agent-card.json` | Server disabled. Set `engine.a2a.server.enabled=true`. |
| `401` on A2A endpoints | Missing/wrong credentials. Check `ENGINE_SECURITY_MODE` and `OPENCODE_SERVER_PASSWORD`. |
| `403` on task read | Task ownership: only the creating principal can access the task. |
| Agent not in discovery list | Endpoint `enabled=false`, fetch failed, or egress blocked. Check `/api/a2a/agents` for `status` and `error`. |
| Card shows wrong URL | `engine.a2a.server.base-url` doesn't match the public URL. Fix and restart. |
| Remote agent not called | Tool not registered: check logs for `Registered A2A tool` or fetch errors. |
| Cache stale | Use `POST /api/a2a/agents/refresh` or `?refresh=true`. |
| No skills in card | Agent has no visible skills in `AgentCatalogue`, or `agent` config points to a non-existent agent. |