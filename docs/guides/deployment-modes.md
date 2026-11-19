# QOC Deployment Modes: Server, Serverless, Worker

Status: Design/decision document
Date: 2026-11-04

This document explains the three operating modes of QOC, their responsibilities,
their agent-config sources, tool policies, workspace semantics, and identity
propagation. It distinguishes between the **implemented current state** and the
**planned target state**.

## 1. Overview

| | Server Mode | Serverless Mode | Worker Mode (planned) |
|---|---|---|---|
| `ENGINE_MODE` | `server` | `serverless` | `server` |
| `ENGINE_ROLE` | `server` | – | `worker` |
| Topology | long-lived server/VM | Function/Cloud Run | long-lived server/VM |
| Workspace | persistent | ephemeral per call | **shared, persistent** |
| Agent authoring | yes (role `admin`/`agent:write`) | no | **no** |
| Agent source | local (Settings + Markdown) | on-demand fetch + inline | local read-only + optional fetch |
| Default agents | all configured | `build`, `plan` | all (read-only) |
| Skills | locally available | not available (MVP) | locally read-only |
| MCP | local definitions | local definitions + agent filter | local definitions + agent filter |
| Tools | full catalog | leaf profile (restricted) | full/configurable |
| Session cleanup | no | yes (ephemeral) | no |
| Identity | User/Admin | caller token (delegated) | service or delegated user |
| Main benefit | orchestration, authoring, interactive work | parallel, independent subtasks | multiple workers on a shared workspace |

---

## 2. Server Mode (`ENGINE_MODE=server`)

### Purpose

Control plane: orchestrator, workflow editor, interactive sessions, authoring
of agents and workflows. The server holds the workspace and the context.

### Agent config sources

At startup and on `reloadConfig()`:

1. Global `settings.json` (`~/.config/opencode/settings.json`) → `agents[]`
2. Project `settings.json` (`<workspace>/settings.json`) → `agents[]`,
   provided `engine.settings.workspace-enabled=true`
3. Global Markdown agents: `~/.config/opencode/agents/*.md`
4. Project Markdown agents: `.opencode/agents/*.md` in the workspace and in
   parent directories
5. Baseline: `build` and `plan` are always added if missing

The merged agent list lands in the `AgentCatalogue`. The full definition is
available via `AgentCatalogue.config(name)` and `GET /api/agent/{name}`.

### Agent authoring

- `POST /api/agent` – create an agent from Markdown
- `PUT /api/agent/{name}` – update
- `DELETE /api/agent/{name}` – delete
- Prerequisite: role `admin` (`requireRole("admin")`) and
  `ENGINE_AGENT_AUTHORING_ENABLED=true`
- Files are written to `~/.config/opencode/agents/<name>.md`;
  then `reloadConfig()`
- `GET /api/agent/{name}` returns `{name, content?, config, hash?, updatedAt?}`

### Workspace and sessions

- Persistent workspace under `ENGINE_WORKSPACE`
- OIDC users get a subdirectory `<workspace>/<user>` (exceptions:
  `opencode`, `anonymous`, `local` → base workspace)
- Sessions default to `mode=workspace`; `mode=ephemeral` is possible
- Workspace switch via `/api/workspace` (Admin)
- Ownership is currently **directory-based** (`Engine.isOwner`); an explicit
  `owner` field in `SessionInfo` is still missing

### Tools

The full tool catalog is registered:

- Workspace: `read`, `write`, `edit`, `patch`, `glob`, `grep`, `shell`
- Worktrees: `worktree_create`, `worktree_list`, `worktree_remove`
- Sessions: `session_move`, `session_rename`
- Agents: `subagent`, `task`
- Skills: `skill`, `skill_list`
- Web: `webfetch`, `websearch`
- MCP: dynamically per configured server
- Further: `question`, `todowrite`, `models`, A2A tools, email tools

The agent config restricts:

- `tools.allow` / `tools.deny`
- `permissions[]` (action/resource/effect)
- `mcpServers[]` (namespace allow-list)
- `skills.enabled/allow/deny/max`

### Skills

- Sources: `<workspace>/skill`, `<workspace>/skills`,
  `<workspace>/.opencode/skills`, plus `skills.paths`
- Global filter (`skills.allow/deny/max`) and per-agent filter
- `skill` loads the body on demand, `skill_list` lists metadata
- At prompt time only the catalog (ID, name, description) is rendered

### MCP

- Definitions from the `"mcp"` block of `settings.json`
- Local servers (`type=local`, Command/Env) and remote servers
  (`type=remote`, URL/Headers/OAuth)
- Registration and tool discovery at startup (`McpServerManager`)
- The agent filters namespaces via `mcpServers`

### Identity and authorization

- OIDC (Keycloak/Auth0) or Basic/None
- `OidcPrincipalProducer` → `PrincipalContext` (`id`, `name`, `roles`,
  `groups`, `claims`, `accessToken`)
- Agent authoring: `admin`
- Other admin endpoints: `admin`
- `X-QOC-User` header is additionally accepted on some endpoints
  (see the identity section)

### Status

Fully implemented.

---

## 3. Serverless Mode (`ENGINE_MODE=serverless`)

### Purpose

Stateless **leaf worker** for independent, parallelizable subtasks:
web research, URL extraction, MCP operations, batch processing. No shared
workspace, no nested subagents.

### Agent config sources

Priority:

1. **Inline config** via `qoc_run_agent_with_config`
   - The orchestrator sends agent Markdown directly in the MCP call
   - Parsed via `AgentMarkdownLoader.parseContent`
   - Materialized in-memory as a transient agent
   - Removed after the call; no filesystem write access
2. **Local catalog**
   - Only `build` and `plan` (plus global Markdown agents, usually empty in
     the container)
   - Settings `agents[]` and project `.opencode/agents` are **ignored** in
     serverless mode
3. **On-demand fetch from the server**
   - `GET {ENGINE_AGENT_SOURCE_URL}/api/agent/{name}`
   - `Authorization: Bearer <token of the calling MCP client>`
   - Fallback: `ENGINE_AGENT_SOURCE_TOKEN`
   - Only if the agent is not available locally (`!hasAgent`)
   - Response: `{name, content?, config, hash?, updatedAt?}`
   - `config` is materialized in-memory as a transient agent
   - After the call: `removeTransientAgent`, no cache
4. **Error** `agent not available` if no fetch is possible

Important configuration:

```env
ENGINE_MODE=serverless
ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074   # registry/server base, not /mcp
ENGINE_AGENT_SOURCE_TOKEN=...                              # optional fallback
```

Only `config` is transferred. **Not** transferred:
- MCP server definitions (only the namespace allow-list `mcpServers`)
- Skill contents (only the skill filter)
- Workspace contents, provider secrets, global skill filter

### Lifecycle of a call

```
Client/Orchestrator
  └─ POST /mcp  (Bearer <user-token>)
       └─ qoc_run_agent(agent="chat", prompt=...)
            ├─ Agent local? build/plan → yes: directly
            ├─ otherwise: GET {SOURCE}/api/agent/chat (Bearer Caller)
            │    └─ config → defineTransientAgent (in-memory)
            ├─ createSession(mode=ephemeral, retain=false)
            ├─ execute prompt
            └─ finally: removeTransientAgent
                 + deleteSession → delete ephemeral directory
```

### Workspace and sessions

- Goal: `mode=ephemeral` per call
- `createSession(..., "ephemeral", false)` creates a session directory under
  `ENGINE_SESSION_TEMP_DIR`
- `deleteSession` removes it again
- No shared workspace
- **Current state:** The MCP default is still `workspace`; switching to
  `ephemeral` and automatic session cleanup are planned

### Tools (leaf profile)

> **Worktrees (opt-in).** `worktree_create`/`worktree_list`/`worktree_remove`
> are disabled by default (`engine.worktree.enabled=false` /
> `ENGINE_WORKTREE_ENABLED=false`). Activation additionally requires `git` and
> a Git working tree in `ENGINE_WORKSPACE`. See
> `docs/diagrams/10-deployment.md`.

Target policy:

| Allowed | Forbidden |
|---|---|
| `webfetch`, `websearch` | `worktree_create/list/remove` |
| `read`, `write`, `edit`, `patch`, `glob`, `grep` (scratch only) | `question` |
| `todowrite` | `subagent`, `task` |
| `shell` (scratch only) | `session_move`, `session_rename` |
| MCP tools (namespace-filtered) | `skill`, `skill_list` (MVP) |
| `list_mcp_resources`, `read_mcp_resource` | `models` |

Configuration (planned):

```env
ENGINE_SERVERLESS_TOOL_PROFILE=leaf
ENGINE_SERVERLESS_ALLOW_TOOLS=...
ENGINE_SERVERLESS_DENY_TOOLS=...
```

**Current state:** The leaf policy is not yet implemented; the serverless
container currently registers the same tool catalog as the server.

### Skills

Not supported in the MVP:

- No skill files in the empty serverless workspace
- No remote skill loading
- The orchestrator inlines the required instructions in the prompt

Later optionally:
- `GET /api/skill/{id}` for bodies + supporting files
- Skill catalog in the agent fetch
- `SkillContentSource` port in the engine, lazy loading with per-call cache
- Virtual `baseDirectory` (`remote://skill/<id>`), no server paths rendered

### MCP

- Definitions come from the **local** serverless configuration
  (`settings.json` `"mcp"` block or deployment env)
- The agent filters namespaces via `mcpServers`
- Missing namespace: warning in `_meta.warnings` (planned), optional
  `ENGINE_SERVERLESS_MCP_STRICT=true` → error
- No MCP secrets from the server

### Identity

- The serverless container validates the Bearer token of the MCP caller
- `principalContext.get().id()` is used as the session user
- The raw token is forwarded to the agent fetch
- The worker acts **as the caller**, not as its own service user
- MCP server calls themselves currently receive no user token (global
  credentials); per-user credentials are open

### Limits and concurrency

- `ENGINE_TURN_MAX_WALL_MINUTES=10`
- `ENGINE_MAX_CONCURRENT_PROMPTS`: 1 for Lambda/Cloud Run, 4 for local
  parallel tests
- Horizontal scaling via instances
- Multiple synchronous MCP calls in parallel from the orchestrator
- Result: text + optional `structuredContent` + `_meta`
  (tokens, finish, warnings, `agentCreated`, `agentDeleted`)

### Status

- On-demand agent fetch: **implemented**
- Inline `qoc_run_agent_with_config`: **implemented**
- Default `build`/`plan`: **implemented**
- Leaf tool policy, default `ephemeral`, automatic session cleanup,
  MCP warnings: **planned**

---

## 4. Worker Mode (`ENGINE_ROLE=worker`, planned)

### Purpose

Run multiple workers on a **shared, persistent workspace** without allowing
them to write agent configurations. The editor/orchestrator remains the only
writer.

### Distinction from serverless

| | Serverless | Worker |
|---|---|---|
| Workspace | ephemeral, no shared space | shared, persistent |
| Agent source | on-demand fetch + inline | read-only files + optional fetch |
| Skills | none (MVP) | locally read-only |
| Default agents | `build`, `plan` | all configured |
| Use case | parallel I/O subtasks | long-lived workers on shared files |

`serverless` is therefore **not** the right mode for a VM worker with a shared
workspace.

### Agent config sources

1. `<shared-workspace>/.opencode/agents/*.md` (read-only)
2. `~/.config/opencode/agents/*.md` (read-only)
3. `settings.json` → `agents[]` (read-only)
4. optional: `GET {ENGINE_AGENT_SOURCE_URL}/api/agent/{name}`

For the optional fetch, the current coupling to `engine.isServerlessMode()`
must be decoupled: always allow fetch when a source URL is set.

### Authoring

- `ENGINE_AGENT_AUTHORING_ENABLED=false`
- `POST/PUT/DELETE /api/agent` → 403
- Filesystem: agent directories mounted read-only
- Only the editor/orchestrator (control plane) writes
- Recommended scopes: worker only `agent:read` + `mcp:run`

### Workspace and sessions

- `ENGINE_WORKSPACE_MODE=shared`
- Persistent workspace
- Sessions are retained
- Workers may edit task files, but not the agent configuration

### Tools

- Full workspace tool catalog by default
- Configurable via a worker policy
- `shell` and file tools are possible on the shared workspace

### Skills and MCP

- Skills from the shared workspace (read-only) and global sources
- MCP definitions local; agent filters namespaces
- No authoring rights for skills/agents

### Identity and exclusive write rights

Three levels:

1. **API authorization:** `agent:write` only for editor/admin; worker
   `agent:read`
2. **Filesystem:** agent config read-only for workers
3. **Single writer + versioning:** editor writes, workers read with
   `hash`/`updatedAt`/ETag

Additionally: audit log for agent/workflow writes and admin lock for other
mutating endpoints (`/api/config/reload`, workspace switch, session move).

### Status

Not implemented. Requires:
- `ENGINE_ROLE=server|worker`
- `ENGINE_WORKSPACE_MODE=shared|ephemeral`
- Decoupling agent fetch from `ENGINE_MODE`
- Authz scopes and read-only mounts

---

## 5. Comparison matrix (details)

| Dimension | Server | Serverless | Worker |
|---|---|---|---|
| Agent from `settings.json` | yes | no | yes (read-only) |
| Agent from project `.opencode/agents` | yes | no | yes (read-only) |
| Agent from global Markdown | yes | yes (usually empty) | yes (read-only) |
| On-demand fetch | no | yes | optional (planned) |
| Inline config | via API/file | `qoc_run_agent_with_config` | via API/file |
| Authoring API | yes (admin) | no (403) | no (403) |
| Default agents | all | build/plan | all |
| Skills | local | none (MVP) | local read-only |
| MCP definitions | local | local | local |
| MCP namespace filter | yes | yes | yes |
| Workspace | persistent | ephemeral | shared persistent |
| Shell | full | scratch only (planned) | full |
| Subagents | yes | no (planned) | yes/configurable |
| Session cleanup | no | yes (planned) | no |
| Identity | User/Admin | caller-delegated | service/delegated |

---

## 6. Identity and user propagation

### Current state

1. **Browser:** OIDC Authorization Code + PKCE; token in `sessionStorage`;
   `Authorization: Bearer` for API/SSE.
2. **Quarkus:** OIDC validates the JWT.
3. **`OidcPrincipalProducer`:** builds a `Principal` from `sub`,
   `preferred_username`, roles (Keycloak `realm_access.roles`, Auth0
   `roles`/namespaced claims), groups and claims; stores it in
   `PrincipalContext` (request-scoped), including the raw token.
4. **`HttpApi`:**
   - `POST /api/session`: `X-QOC-User` first, then Principal ID
   - `POST /api/run`: only `X-QOC-User`, fallback `anonymous`
   - `GET /api/session`/`{id}`: Principal ID for ownership
   - Agent authoring: `admin`
5. **`Engine.createSession`:** user ID → `<workspace>/<user>` (except
   `opencode`/`anonymous`/`local`).
6. **`Engine.isOwner`:** checks only the directory prefix.
7. **`McpServerResource`:** user = Principal ID; raw token is forwarded to
   the agent fetch.
8. **`ToolContext`:** contains **no** principal.
9. **A2A:** Basic or trusted-proxy header.

### Known gaps

- `X-QOC-User` is spoofable in OIDC mode; `POST /api/run` does not use the
  authenticated principal.
- `SessionInfo` has no `owner` field; ownership depends on the directory
  name.
- Roles are barely enforced (only `admin`); no `agent:read`/
  `skill:read`/`mcp:run` scopes.
- No principal in `ToolContext`; async tools can lose the request scope.
- MCP servers receive no per-user token; global credentials.
- Raw token is only forwarded for the agent fetch.

### Target state

- `sub` as stable owner key; `SessionInfo.owner` + migration
- Ignore `X-QOC-User` in OIDC mode; admin impersonation only explicitly
  (`act-as`) and auditable
- Scopes: `qoc-admin`, `qoc-editor` (`agent:write`), `qoc-user`
  (`agent:read`, `mcp:run`, own sessions), `qoc-worker` (`agent:read`,
  `mcp:run`)
- Principal in `ToolContext` / `ExecutionIdentity`
- Token exchange (RFC 8693) for outgoing calls (`aud=mcp-server`,
  `aud=qoc-engine`), `act` claim for delegation
- Per-user credentials encrypted, no global MCP token
- User-triggered workers: delegated user token; system tasks: service identity

---

## 7. Decisions (Serverless MVP)

| # | Question | Decision |
|---|---|---|
| 1 | `shell` in the worker | allowed locally in the ephemeral scratch |
| 2 | `write`/`edit` in scratch | yes |
| 3 | `subagent`/`task` | deny |
| 4 | `max-concurrent-prompts` | configurable: 1 (Lambda/Cloud Run) or 4 (local/VM) |
| 5 | MCP namespace missing | warning in `_meta.warnings`, optional strict mode |

---

## 8. Implementation plan

### Serverless MVP

1. Leaf tool policy in `Engine.filterToolSnapshot` (serverless mode)
2. Default `mode=ephemeral` in `McpServerResource.toolsCall`
3. Session cleanup after the call (`sessionMode=new`, `retain=false`)
4. `_meta.warnings` for missing MCP namespaces; optional strict mode
5. Serialize `skills.filter` in `EngineConfig.agentToJson` (bugfix)
6. Compose: `ENGINE_SERVERLESS_TOOL_PROFILE=leaf`,
   `ENGINE_SESSION_TEMP_DIR=/tmp/qoc-sessions`,
   `ENGINE_MAX_CONCURRENT_PROMPTS=1|4`

### Worker Mode

1. Introduce `ENGINE_ROLE=server|worker`
2. `ENGINE_WORKSPACE_MODE=shared|ephemeral`
3. Decouple agent fetch from `ENGINE_MODE` (only source URL decides)
4. Enforce authoring via authz + read-only mounts
5. Enforce scopes `agent:read`/`agent:write`/`mcp:run`
6. `SessionInfo.owner` + DB migration
7. Propagate principal into `ToolContext`

### Tests

- Serverless: leaf policy, ephemeral cleanup, parallel calls,
  MCP filter/warnings, inline config without filesystem
- Worker: read-only agent config, 403 on authoring, shared workspace
- Identity: no `X-QOC-User` spoofing in OIDC mode, owner field,
  roles/scopes
- JaCoCo ≥ 80 %

---

## 9. Open points

- Fire-and-forget async jobs or synchronous MCP calls with orchestrator
  parallelism?
- Artifact handoff: structured output vs. object storage vs. MCP
- Remote skills: catalog in agent fetch or separate endpoint?
- MCP config: strictly deployment-local (recommended) or sanitized from
  server?
- Finalize scope names and role model
- Worker role: own `ENGINE_ROLE` axis or only capability flags?

---

## 10. Subagent delegation

The design for delegation to local, serverless, and server workers via the
`subagent` tool – including worker pool, routing, MCP proxy variants, and
cloud topologies – is in `guides/Subagent-Delegation.md`.

## 11. Registry role (optional)

QOC can run without execution as an **agent registry**:

```env
ENGINE_MODE=server
ENGINE_ROLE=registry
ENGINE_AGENT_AUTHORING_ENABLED=true
```

- Agent CRUD/read (`/api/agent`) remains available.
- Execution (`/api/run`, sessions, MCP tool calls, A2A) returns HTTP 503.
- Serverless and VM workers use `ENGINE_AGENT_SOURCE_URL` against the
  registry.

Details: `guides/Agent-Registry.md`.