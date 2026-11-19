# 04 – Docker E2E: A2A and MCP across three containers

> **Subject:** End-to-end test concept for the interoperability of QOC across two
> protocols (A2A, MCP) in three containers with different agents.
>
> **Basis:** `docs/analysis/a2a.md` (Phase 1–3), `docs/spec/tools/04-mcp.md`,
> `docs/spec/tools/09-mcp-server.md`, `docs/security.md` §4.8a, `deploy/Dockerfile`.
>
> **Status:** 2026-10-28 · Spec document (no implementation).

## 1. Goal

Verify that a QOC agent (Container A) works with two different protocols
simultaneously:

- **A2A** to a second QOC agent (Container B) – agent-to-agent,
- **MCP** to a third container (Container C) – QOC as MCP client to an
  MCP server.

And conversely, that Container A itself is reachable as an **A2A server**
and as an **MCP server**. The three containers run isolated, without
external LLMs (scripted provider), and exchange real JSON-RPC messages.

**Out of scope:** OIDC (separate test plan `docs/plans/e2e-security-test.md`),
performance/load, real LLM responses.

## 2. Container topology

```
┌─────────────────────────────────────────────────────────────────────┐
│  Docker network: qoc-interop (bridge)                              │
│                                                                     │
│  ┌──────────────┐   A2A    ┌──────────────┐   MCP    ┌────────────┐ │
│  │  qoc-agent-a │ ◄──────► │  qoc-agent-b │          │ mcp-server │ │
│  │  (Orchestr.)  │          │  (Peer)      │          │ (Container │ │
│  │  A2A srv+cli  │ ─────────────────────────────────► │  C)        │ │
│  │  MCP cli+srv  │          │              │          │            │ │
│  └──────┬───────┘          └──────────────┘          └────────────┘ │
│         │                                                            │
│         └── published: 127.0.0.1:8078 (only A)                      │
└─────────────────────────────────────────────────────────────────────┘
```

| Container | Role | Protocols | Agent name |
|---|---|---|---|
| `qoc-agent-a` | Orchestrator (SUT) | A2A server, A2A client, MCP client, MCP server | `Agent A` |
| `qoc-agent-b` | Peer agent | A2A server (only) | `Agent B` |
| `mcp-server` | MCP server | MCP server (`qoc_run_agent` or own tool set) | – |

**Rule:** Container A is the only one that uses both protocols **as a client**.
Containers B and C are pure servers. This makes the data flow unambiguous
and debuggable.

## 3. Agents and their tasks

| Agent | Container | Description | Expected response (scripted) |
|---|---|---|---|
| `agent-a` | A | primary agent, uses A2A tool `a2a_agent_b` and MCP tool `mcp_echo` | "Hallo aus der Session Engine." |
| `agent-b` | B | peer agent, responds to A2A `SendMessage` | "Hallo aus der Session Engine." |
| `mcp-echo` | C | MCP server with tool `echo(text)` (or `qoc_run_agent` on B) | Echo of the input text |

The scripted provider (`ENGINE_PROVIDER_SCRIPTED=true`) delivers deterministic
responses, so no LLM API is needed.

## 4. Configuration

### 4.1 Common base (all QOC containers)

```env
ENGINE_PROVIDER_SCRIPTED=true
ENGINE_DB_KIND=sqlite
ENGINE_DB_PATH=/data/qoc.db
ENGINE_WORKSPACE=/data/workspace
ENGINE_SECURITY_MODE=basic
OPENCODE_SERVER_PASSWORD=interop-secret
QUARKUS_OTEL_ENABLED=false
```

### 4.2 Container A – `qoc-agent-a`

```env
ENGINE_A2A_SERVER_ENABLED=true
ENGINE_A2A_SERVER_NAME=Agent A
ENGINE_A2A_SERVER_BASE_URL=http://qoc-agent-a:8080
ENGINE_A2A_SERVER_AGENT=none

ENGINE_A2A_ENDPOINTS=id=agent-b|url=http://qoc-agent-b:8080|tool=a2a_agent_b|enabled=true
ENGINE_A2A_CACHE_TTL_SECONDS=60

ENGINE_MCP_SERVER_ENABLED=true
ENGINE_MCP_SERVER_OAUTH_ENABLED=false
```

`settings.json` (in the workspace or global) for the MCP client:

```json
{
  "mcp": {
    "echo": {
      "type": "remote",
      "url": "http://mcp-server:8090/mcp",
      "disabled": false,
      "codemode": true
    }
  }
}
```

### 4.3 Container B – `qoc-agent-b`

```env
ENGINE_A2A_SERVER_ENABLED=true
ENGINE_A2A_SERVER_NAME=Agent B
ENGINE_A2A_SERVER_BASE_URL=http://qoc-agent-b:8080
ENGINE_A2A_SERVER_AGENT=none
ENGINE_A2A_URLS=
ENGINE_MCP_SERVER_ENABLED=false
```

### 4.4 Container C – `mcp-server`

Option A: QOC as MCP server (`ENGINE_MCP_SERVER_ENABLED=true`, otherwise
disabled).
Option B: a minimal MCP reference server (e.g. Python `mcp` SDK) with tool
`echo(text)`. Option B is sufficient for a pure protocol test and makes the
suite independent of QOC's own MCP server details.

**Recommendation:** Option B for the protocol test, Option A for the
QOC-MCP-server test (separate run).

## 5. Docker Compose (sketch)

```yaml
services:
  qoc-agent-a:
    image: qoc-engine:local
    container_name: qoc-agent-a
    environment:
      - ENGINE_A2A_SERVER_ENABLED=true
      - ENGINE_A2A_SERVER_BASE_URL=http://qoc-agent-a:8080
      - ENGINE_A2A_ENDPOINTS=id=agent-b|url=http://qoc-agent-b:8080|tool=a2a_agent_b|enabled=true
      - ENGINE_MCP_SERVER_ENABLED=true
      - ENGINE_SECURITY_MODE=basic
      - OPENCODE_SERVER_PASSWORD=interop-secret
      - ENGINE_PROVIDER_SCRIPTED=true
    volumes:
      - ./interop/settings-a.json:/data/workspace/settings.json:ro
    ports: ["8078:8080"]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/q/health/ready"]
      interval: 10s
      timeout: 5s
      retries: 20

  qoc-agent-b:
    image: qoc-engine:local
    container_name: qoc-agent-b
    environment:
      - ENGINE_A2A_SERVER_ENABLED=true
      - ENGINE_A2A_SERVER_BASE_URL=http://qoc-agent-b:8080
      - ENGINE_SECURITY_MODE=basic
      - OPENCODE_SERVER_PASSWORD=interop-secret
      - ENGINE_PROVIDER_SCRIPTED=true
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/q/health/ready"]
      interval: 10s
      timeout: 5s
      retries: 20

  mcp-server:
    image: python:3.12-alpine
    container_name: mcp-server
    command: ["python", "/app/server.py"]
    volumes:
      - ./interop/mcp_server.py:/app/server.py:ro
    ports: ["8079:8090"]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8090/health"]
      interval: 10s
      timeout: 5s
      retries: 20
```

> **Implementation:** The stack lives as profile `a2a` in
> `deploy/docker-compose.yml` (`qoc-agent-a`, `qoc-agent-b`, `mcp-server`).
> The image is `qoc-engine:sqlite` from `deploy/build-image-sqlite.sh`; start
> with `cd deploy && docker compose --profile a2a up -d`. Host ports: 8078
> (Agent A), 8079 (MCP).

## 6. Protocol flows

### 6.1 A2A: A → B

1. A reads B's agent card: `GET http://qoc-agent-b:8080/.well-known/agent-card.json`
2. A sends `SendMessage` (JSON-RPC, method `SendMessage`) with Basic Auth
   (`opencode`/`interop-secret`).
3. B creates a QOC session, the scripted provider responds, B emits an
   artifact with text.
4. A receives the task with `status.state = TASK_STATE_COMPLETED` and reads
   `artifacts[0].parts[0].text`.

### 6.2 MCP: A → C

1. A initializes the MCP client against `http://mcp-server:8090/mcp`
   (`initialize`, `tools/list`).
2. A calls `tools/call` with `echo`/`qoc_run_agent`.
3. C responds with `content` (text) or `structuredContent`.

### 6.3 Combo: A uses both protocols in one turn

1. A receives a prompt "Summarize the answer from B and the echo from C."
2. A calls the A2A tool `a2a_agent_b` (→ B).
3. A calls the MCP tool `mcp_echo` (→ C).
4. A combines both results in the final answer.

Since the scripted provider emits no real tool calls, this combination is
executed as a **manual test** (curl/SDK) or via a test runner with an
SDK client, not through the LLM path.

## 7. Test matrix

### A2A scenarios

| ID | Given | When | Then |
|---|---|---|---|
| A2A-01 | B runs, A configured | `GET A/.well-known/agent-card.json` | 200, name `Agent A`, JSON-RPC interface |
| A2A-02 | B runs | `GET B/.well-known/agent-card.json` without credentials | 200 (card is public) |
| A2A-03 | Basic Auth active | `POST B/` `SendMessage` without credentials | 401 |
| A2A-04 | Basic Auth active | `POST B/` `SendMessage` with wrong password | 401 |
| A2A-05 | Basic Auth active | `POST B/` `SendMessage` with correct credentials | 200, `result.kind == "task"`, `status.state == "TASK_STATE_COMPLETED"`, text contains "Session Engine" |
| A2A-06 | B stopped | A calls discovery `GET A/api/a2a/agents` | `data[0].status == "error"` |
| A2A-07 | B restarted | `POST A/api/a2a/agents/refresh` | `data[0].status == "ok"`, `cached == false` |
| A2A-08 | A2A server disabled (`ENGINE_A2A_SERVER_ENABLED=false`) | `GET A/.well-known/agent-card.json`, `POST A/` | 404 |
| A2A-09 | Two users (Basic: `opencode`/`interop-secret`) | User A creates task, User B calls `tasks/get` | 403 (task ownership) |
| A2A-10 | Cache TTL 2 s | Two `GET`s within TTL, then wait 3 s | first/second `cached == true`, third `cached == false` |

### MCP scenarios

| ID | Given | When | Then |
|---|---|---|---|
| MCP-01 | C runs | A `tools/list` via MCP | `echo` (or `qoc_run_agent`) present |
| MCP-02 | C runs | A `tools/call` with `echo` | response contains the sent text |
| MCP-03 | C stopped | A calls MCP tool | error, other servers untouched (isolation) |
| MCP-04 | OAuth disabled | `GET A/mcp/authorize` | 503 |
| MCP-05 | A as MCP server active | external client `tools/list` against A | exactly one tool: `qoc_run_agent` |
| MCP-06 | A as MCP server active | external client calls `qoc_run_agent` with `agent=agent-a`, `prompt=Hallo` | response contains "Session Engine" and `_meta.sessionId` |

### Combo scenarios

| ID | Given | When | Then |
|---|---|---|---|
| COMBO-01 | A, B, C run | A executes A2A-`SendMessage` to B and MCP-`tools/call` to C sequentially | both responses correct, no mixing of contexts |
| COMBO-02 | A configured for both protocols | `GET A/api/a2a/agents` | only B listed (MCP servers are not part of A2A discovery) |
| COMBO-03 | A active as A2A and MCP server | external client uses both endpoints (`/.well-known/agent-card.json`, `/mcp`) | both respond, no mutual blocking |

## 8. Automation

- **Build:** `deploy/build-image.sh` produces `qoc-engine:local`.
- **Runner:** container `a2a-mcp-test` (Alpine + curl + jq + Python) runs
  `run.sh` and exits with code 0/1.
- **A2A payloads:** SDK format (method `SendMessage`, `GetTask`), generated
  from `org.a2aproject.sdk` or as fixed JSON files under
  `interop/payloads/`.
- **MCP payloads:** JSON-RPC 2.0 `initialize`, `tools/list`, `tools/call`.
- **Logs:** `docker compose logs` after each run; on error `docker compose down -v`
  and restart.

## 9. Acceptance criteria

- All scenarios A2A-01…A2A-10, MCP-01…MCP-06, COMBO-01…COMBO-03 green.
- No secrets in logs (`interop-secret` must not appear in plaintext).
- Container A survives stopping B and C (isolation).
- Discovery cache is reloaded on refresh.
- MCP server exposes exactly `qoc_run_agent` (M-01).
- A2A server is disabled by default (404) and only active with flag.

## 10. Evidence (mapping to rules)

| Rule | Test ID | Type | Status |
|---|---|---|---|
| A2A client egress | A2A-01, A2A-05 | Integration | open |
| A2A server opt-in (404) | A2A-08 | Integration | open |
| A2A auth (Basic) | A2A-03, A2A-04, A2A-05 | Integration | open |
| A2A task ownership | A2A-09 | Integration | open |
| A2A cache | A2A-10 | Integration | open |
| A2A discovery | A2A-06, A2A-07 | Integration | open |
| MCP M-01 (exactly one tool) | MCP-05 | Integration | open |
| MCP M-04 (OAuth inactive → 503) | MCP-04 | Integration | open |
| MCP M-05 (delegation to engine) | MCP-06 | Integration | open |
| Interop isolation | A2A-06, MCP-03, COMBO-01 | Integration | open |

## 11. Limits

- No real LLM path: tool calls are triggered directly via SDK/HTTP, not
  through the model loop. The model loop with real tool calls remains in the
  JVM tests (`A2AServerTest`).
- No OIDC in this concept; OIDC runs as a separate profile with Keycloak
  (`docs/plans/e2e-security-test.md` §4, §8).
- No performance test; timeouts are sanity checks, not benchmarks.
- Container C can be either the QOC MCP server or a reference MCP server;
  the results are not 1:1 comparable.
