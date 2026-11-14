# Einheitliche Speicher-Konfiguration

> **Status:** 2026-11-14
> **Geltung:** `qoc/` — Java 21 / Quarkus 3.40

## 1. Grundprinzip: `engine.storage` steuert alles

Ein einziger Schalter bestimmt das Backend für alle **Daten**-Oberflächen:

```properties
engine.storage=memory   # oder: file, sqlite, postgres
```

| `engine.storage` | Engine-Runtime | Workflows | Agents | MCP-Server | QOC-Agents |
|---|---|---|---|---|---|
| `memory` | In-Memory (ephemeral) | In-Memory | In-Memory | Runtime-Registry (ephemeral) | Import aus MD → Memory |
| `file` | In-Memory (ephemeral) | File (JSON) | File (JSON) | File (JSON) | File (MD) |
| `sqlite` | SQLite DB | SQLite DB | SQLite DB | File (JSON) | Import → SQLite |
| `postgres` | Postgres DB | Postgres DB | Postgres DB | File (JSON) | Import → Postgres |

### Warum Runtime bei `file` in-memory bleibt

Die Engine-Runtime (Sessions, Journal, Snapshots, Tool-Outputs) hat keinen
File-Backend. Bei `engine.storage=file` wird die Runtime wie bei `memory`
behandelt (kein Datasource, keine Crash-Recovery). Workflows, Agents und
MCP-Server nutzen Dateien.

## 2. Konfiguration vs. Daten

| Oberfläche | Art | Backend | Begründung |
|---|---|---|---|
| `EngineConfig` (Provider, Model, Permissions, Agent-Defaults) | Konfiguration | Datei (JSON/properties) | Konfiguration ≠ Daten |
| `application.properties` | Konfiguration | Datei | Quarkus-Standard |
| QOC-Agents (Markdown) | Templates | Datei (MD) | Templates, nicht Runtime-Daten |
| MCP-Server-Defaults (Config) | Konfiguration | Datei (JSON) | Initiale Server-Liste |
| Engine-Runtime | Daten | Memory/DB | Sessions, Journal |
| Workflow-Definitionen | Daten | Memory/File/DB | Nutzerdaten |
| Agent-Definitionen (SSOT) | Daten | Memory/File/DB | Nutzerdaten |
| MCP-Server (UI-registriert) | Daten | File (JSON) | Runtime-Registrierungen |

## 3. Override-Möglichkeiten

`engine.workflow.storage` und `engine.agent.storage` können den Default
überschreiben (`auto` folgt `engine.storage`):

```properties
engine.storage=postgres
engine.workflow.storage=file      # Workflows in Dateien, Runtime in DB
engine.agent.storage=memory      # Agents in-memory (ephemeral)
```

## 4. File-First, DB-Override

Beim Start werden Dateien **zuerst** geladen, DB-Daten haben **Vorrang**:

- **Agents:** `WorkflowRecovery.importQocAgents()` importiert Config- und
  Markdown-Agents in den Agent-Store, aber nur wenn `store.get(id) == null`
  (DB-Daten werden nicht überschrieben).
- **Workflows:** `WorkflowRecovery.importFileWorkflows()` scannt `wf-data/`
  für `*.json`-Dateien und importiert fehlende Workflows (DB hat Vorrang).
- **MCP-Server:** `McpRegistry.setup()` lädt persistierte Server aus
  `mcp-servers.json` (UI-registrierte Server überleben Neustarts).

## 5. MCP-Server

### Konfiguration (Datei)

In `settings.json` oder über Umgebungsvariablen:

```json
{
  "mcp": {
    "test": { "url": "/api/test/mcp" },
    "firecrawl": { "url": "https://mcp.firecrawl.dev/v2/mcp" }
  }
}
```

Relative URLs (`/api/test/mcp`) werden automatisch zum Port aufgelöst:
`http://localhost:<port>/api/test/mcp`. Keine Port-Angabe nötig.

### Runtime-Registrierung (UI/REST)

```bash
# Hinzufügen
curl -X POST http://localhost:8080/api/mcp-client/servers \
  -H "Content-Type: application/json" \
  -d '{"name":"test","type":"remote","url":"/api/test/mcp"}'

# Löschen
curl -X DELETE http://localhost:8080/api/mcp-client/servers/test
```

UI: `/workflow/mcp-servers` — Add/Remove/Reload mit Status-Anzeige.

Persistenz: registrierte Server werden in `./mcp-servers.json` gespeichert
und beim nächsten Start geladen.

## 6. Agent-Berechtigungen (RBAC)

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

| Agent-Name | Default `rolesAllowed` |
|---|---|
| `build`, `plan` | `["*"]` (alle) |
| Sonstige | `["admin"]` (nur Admin) |

### Markdown-Frontmatter (QOC-Agents)

```yaml
---
name: my-agent
rolesAllowed: [admin, editor]
---
```

`QocAgentConverter` überträgt `rolesAllowed` in den Agent-Store.

### Workflow-Runner-Prüfung

`DefaultNodeDispatcher.agentAllowed()`:
- `*` in `rolesAllowed` → alle dürfen
- Leere Liste → nur Owner + Admin
- Admin-Principal → immer erlaubt

## 7. Umgebungsvariablen

| Variable | Default | Wirkung |
|---|---|---|
| `ENGINE_STORAGE` | `postgres` | memory / file / sqlite / postgres |
| `ENGINE_DB_KIND` | `postgresql` | sqlite / postgresql |
| `ENGINE_WORKFLOW_STORAGE` | `auto` | auto / memory / file / sqlite / postgres |
| `ENGINE_WORKFLOW_STORAGE_DIR` | `./wf-data` | Verzeichnis für Workflow-JSON-Dateien |
| `ENGINE_AGENT_STORAGE` | `auto` | auto / memory / file / sqlite / postgres |
| `QOC_MCP_SERVERS_FILE` | `./mcp-servers.json` | Persistenzdatei für MCP-Server |

## 8. Offene Punkte

- **MCP-Server DB-Persistenz:** Aktuell JSON-File. DB-Modus könnte eine
  `mcp_server`-Tabelle nutzen (Migration V12).
- **RBAC least-privilege Default als Config-Key:** `engine.agent.default-roles`
  (aktuell hardcoded: build/plan=`*`, sonst=admin).
- **Echter File-Journal für Runtime:** Bei `file`-Modus ist die Runtime
  in-memory (kein Crash-Recovery). Ein File-Journal wäre für Offline-Nutzung
  wünschenswert.
- **Bessere Config-Möglichkeit:** UI-basierte Konfiguration für Agents,
  Permissions und MCP-Server (aktuell nur JSON/Properties).
