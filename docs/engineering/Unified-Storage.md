# Unified Storage Configuration

> **Status:** 2026-11-14
> **Scope:** `qoc/` — Java 21 / Quarkus 3.40

## 1. Core Principle: `engine.storage` Controls Everything

A single switch determines the backend for all **data** surfaces:

```properties
engine.storage=memory   # or: file, sqlite, postgres
```

| `engine.storage` | Engine Runtime | Workflows | Agents | MCP Servers | QOC Agents |
|---|---|---|---|---|---|
| `memory` | In-Memory (ephemeral) | In-Memory | In-Memory | Runtime Registry (ephemeral) | Import from MD → Memory |
| `file` | In-Memory (ephemeral) | File (JSON) | File (JSON) | File (JSON) | File (MD) |
| `sqlite` | SQLite DB | SQLite DB | SQLite DB | File (JSON) | Import → SQLite |
| `postgres` | Postgres DB | Postgres DB | Postgres DB | File (JSON) | Import → Postgres |

### Why the Runtime Stays In-Memory under `file`

The Engine Runtime (sessions, journal, snapshots, tool outputs) has no
file backend. Under `engine.storage=file`, the runtime is treated like
`memory` (no datasource, no crash recovery). Workflows, agents and
MCP servers use files.

## 2. Configuration vs. Data

| Surface | Type | Backend | Rationale |
|---|---|---|---|
| `EngineConfig` (provider, model, permissions, agent defaults) | Configuration | File (JSON/properties) | Configuration ≠ data |
| `application.properties` | Configuration | File | Quarkus standard |
| QOC Agents (Markdown) | Templates | File (MD) | Templates, not runtime data |
| MCP Server Defaults (config) | Configuration | File (JSON) | Initial server list |
| Engine Runtime | Data | Memory/DB | Sessions, journal |
| Workflow Definitions | Data | Memory/File/DB | User data |
| Agent Definitions (SSOT) | Data | Memory/File/DB | User data |
| MCP Servers (UI-registered) | Data | File (JSON) | Runtime registrations |

## 3. Override Options

`engine.workflow.storage` and `engine.agent.storage` can override the
default (`auto` follows `engine.storage`):

```properties
engine.storage=postgres
engine.workflow.storage=file      # Workflows in files, runtime in DB
engine.agent.storage=memory      # Agents in-memory (ephemeral)
```

## 4. File-First, DB-Override

At startup, files are loaded **first**, DB data takes **precedence**:

- **Agents:** `WorkflowRecovery.importQocAgents()` imports config and
  Markdown agents into the agent store, but only when
  `store.get(id) == null` (DB data is not overwritten).
- **Workflows:** `WorkflowRecovery.importFileWorkflows()` scans
  `wf-data/` for `*.json` files and imports missing workflows (DB
  takes precedence).
- **MCP Servers:** `McpRegistry.setup()` loads persisted servers from
  `mcp-servers.json` (UI-registered servers survive restarts).

## 5. MCP Servers

### Configuration (File)

In `settings.json` or via environment variables:

```json
{
  "mcp": {
    "test": { "url": "/api/test/mcp" },
    "firecrawl": { "url": "https://mcp.firecrawl.dev/v2/mcp" }
  }
}
```

Relative URLs (`/api/test/mcp`) are resolved to the port automatically:
`http://localhost:<port>/api/test/mcp`. No port specification needed.

### Runtime Registration (UI/REST)

```bash
# Add
curl -X POST http://localhost:8080/api/mcp-client/servers \
  -H "Content-Type: application/json" \
  -d '{"name":"test","type":"remote","url":"/api/test/mcp"}'

# Delete
curl -X DELETE http://localhost:8080/api/mcp-client/servers/test
```

UI: `/workflow/mcp-servers` — Add/Remove/Reload with status display.

Persistence: registered servers are stored in `./mcp-servers.json`
and loaded on the next start.

## 6. Agent Permissions (RBAC)

### Config (settings.json / application.properties)

```json
{
  "agents": [{
    "name": "my-agent",
    "rolesAllowed": ["admin", "editor"],
    ...
  }]
}
```

### Defaults (`normalizeRoles`)

| Agent Name | Default `rolesAllowed` |
|---|---|
| `build`, `plan` | `["*"]` (all) |
| Others | `["admin"]` (admin only) |

### Markdown Frontmatter (QOC Agents)

```yaml
---
name: my-agent
rolesAllowed: [admin, editor]
---
```

`QocAgentConverter` transfers `rolesAllowed` into the agent store.

### Workflow Runner Check

`DefaultNodeDispatcher.agentAllowed()`:
- `*` in `rolesAllowed` → everyone allowed
- Empty list → owner + admin only
- Admin principal → always allowed

## 7. Environment Variables

| Variable | Default | Effect |
|---|---|---|
| `ENGINE_STORAGE` | `postgres` | memory / file / sqlite / postgres |
| `ENGINE_DB_KIND` | `postgresql` | sqlite / postgresql |
| `ENGINE_WORKFLOW_STORAGE` | `auto` | auto / memory / file / sqlite / postgres |
| `ENGINE_WORKFLOW_STORAGE_DIR` | `./wf-data` | Directory for workflow JSON files |
| `ENGINE_AGENT_STORAGE` | `auto` | auto / memory / file / sqlite / postgres |
| `QOC_MCP_SERVERS_FILE` | `./mcp-servers.json` | Persistence file for MCP servers |

## 8. Open Items

- **MCP Server DB persistence:** Currently JSON file. DB mode could use
  an `mcp_server` table (Migration V12). *(Open — JSON file is the
  documented design; DB persistence would be an enhancement.)*
- **RBAC least-privilege default as config key:** ✅ Implemented via
  `engine.agent.default-roles` (Default: `admin`). Hardcoded fallback for
  `build`/`plan` remains `*`.
- **Real file journal for runtime:** In `file` mode the runtime is
  in-memory (no crash recovery). A file journal would be desirable for
  offline use. *(Open — complex, requires file journal implementation.)*
- **Better configuration option:** UI-based configuration for agents,
  permissions and MCP servers (currently only JSON/Properties). *(Open —
  large scope, requires extensive UI work.)*
