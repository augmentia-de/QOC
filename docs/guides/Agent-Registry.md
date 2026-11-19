# Agent Registry (Option A)

Status: optional, implemented (Registry role in QOC)
Date: 2026-11-04

This variant runs QOC **only as an Agent Registry** — without executing
prompts. It is an optional usage mode; normal server/serverless
operation remains unchanged.

## 1. Purpose

One or more QOC instances provide the agent catalog centrally:

- Read agents (`GET /api/agent`, `GET /api/agent/{name}`)
- Create/update/delete agents (`POST/PUT/DELETE /api/agent`)
- Serverless workers and VM workers fetch their agent config from there
- No prompt, session, or MCP tool operation on the registry

This is the smallest step towards a standalone agent-registry
service: the registry uses the existing QOC API and serialization, but
does not execute anything.

## 2. Architecture

```
Editor / Admin
      │  POST/PUT/DELETE /api/agent
      ▼
QOC Registry (ENGINE_ROLE=registry)
      │  GET /api/agent/{name}   (config, hash, updatedAt)
      ├──────────────┬───────────────────┐
      ▼              ▼                   ▼
 QOC Serverless   QOC Worker (VM)     QOC Server
 (Fetch per call) (optional Fetch)    (optional Cache)
```

The registry is **not** an MCP server and **not** an executor. It only delivers
agent definitions.

## 3. Configuration

```env
ENGINE_MODE=server
ENGINE_ROLE=registry
ENGINE_AGENT_AUTHORING_ENABLED=true
ENGINE_SECURITY_MODE=oidc
```

Important:

- `ENGINE_MODE=server` (not `serverless`) — otherwise workspace agents
  would be ignored and authoring would be locked.
- `ENGINE_ROLE=registry` disables execution, not authoring.
- `ENGINE_AGENT_AUTHORING_ENABLED=true` must remain set.

### Docker (optional)

```bash
cd deploy
docker compose up -d qoc-registry
```

The service uses the `e2e` profile and is fixed to port `8074`
(`ENGINE_MODE=server`, `ENGINE_ROLE=registry`, OIDC against Keycloak).
With the OIDC setup, Keycloak is a prerequisite (`deploy/start-oidc.sh`).

The registry then runs at `http://localhost:8074`.

## 4. API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/agent` | List (`id`, `name`, `hidden`) |
| `GET` | `/api/agent/{name}` | `{name, content?, config, hash?, updatedAt?}` |
| `POST` | `/api/agent` | Create agent from Markdown (`qoc-admin`) |
| `PUT` | `/api/agent/{name}` | Update agent (`qoc-admin`) |
| `DELETE` | `/api/agent/{name}` | Delete agent (`qoc-admin`) |
| `GET` | `/api/health` | includes `role=registry`, `mode=server` |

Not available (HTTP 503, `_tag=RegistryMode`):

- `POST /api/run`
- `POST /api/session`
- `POST /api/session/{id}/resume`
- `POST /api/session/{id}/prompt`
- `POST /api/session/{id}/command`
- MCP `tools/call` (`/mcp`)
- A2A execution (executor rejects)

## 5. Consumers

Serverless workers or VM workers point at the registry:

```env
ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074
# optional:
ENGINE_AGENT_SOURCE_TOKEN=...
```

The fetch still uses:

```text
GET {ENGINE_AGENT_SOURCE_URL}/api/agent/{name}
Authorization: Bearer <Token of the calling MCP client>
```

Thus the registry is the only config supplier; QOC runtimes only
execute.

## 6. Roles and security

- Authoring requires the role `admin` or `qoc-admin`
  (`HttpApi.hasRole` accepts both).
- `qoc-admin` is granted to `bob` in the local Keycloak setup.
- OIDC audience remains `qoc-engine`.
- Audit log for agent writes is available (`/api/audit`, `admin`).
- The registry should **not** be operated publicly without OIDC.

## 7. Limits of this variant

- Agent definitions are still file-based under
  `~/.config/opencode/agents` and in `settings.json`.
- No versioning/ETag per agent (only `hash`/`updatedAt`).
- No dedicated editor UI; the existing QOC UI is reused.
- No tenant separation beyond the existing workspace/user logic.
- Single instance; no HA/DB versioning.

## 8. Roadmap to a standalone service

1. Introduce an `AgentSource` port in QOC (local + remote).
2. Run the registry read API as a standalone service (Option B).
3. Add versioning/ETag/If-None-Match.
4. Extract the authoring UI from QOC.
5. Optionally: Git-backed registry (Option C).

## 9. Verification

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/qoc/protocol/openid-connect/token \
  -d grant_type=password -d client_id=qoc-cli \
  -d username=bob -d password=bob-password | jq -r .access_token)

# Catalog
curl -H "Authorization: Bearer $TOKEN" http://localhost:8074/api/agent

# Read agent
curl -H "Authorization: Bearer $TOKEN" http://localhost:8074/api/agent/plan

# Execution must return 503
curl -i -X POST http://localhost:8074/api/run \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"test"}'
```

## 10. Tests

- `RegistryModeTest`: Health shows `role=registry`; `/api/run` and
  `/api/session` return 503; `/api/agent` remains available; MCP `tools/call`
  returns 503.
- Existing server/serverless tests remain unchanged.

## 11. References

- `quarkus/src/main/java/de/augmentia/oc/engine/http/EngineConfig.java`
  (`engine.role`)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java`
  (`registryBlocked`, Agent endpoints)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/McpServerResource.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/a2a/QocAgentExecutor.java`
- `deploy/docker-compose.yml` (Service `qoc-registry`, profile `e2e`)
- `docs/guides/deployment-modes.md`
