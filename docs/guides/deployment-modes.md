# QOC Deployment Modes: Server, Serverless, Worker

Status: Design-/Entscheidungsdokument
Datum: 2026-11-04

Dieses Dokument erklärt die drei Betriebsarten von QOC, ihre Aufgaben, ihre
Agent-Config-Quellen, Tool-Policies, Workspace-Semantik und Identitätsweitergabe.
Es unterscheidet zwischen **implementiertem Ist-Zustand** und **geplantem
Zielbild**.

## 1. Überblick

| | Server Mode | Serverless Mode | Worker Mode (geplant) |
|---|---|---|---|
| `ENGINE_MODE` | `server` | `serverless` | `server` |
| `ENGINE_ROLE` | `server` | – | `worker` |
| Topologie | langlebiger Server/VM | Function/Cloud Run | langlebiger Server/VM |
| Workspace | persistent | ephemer pro Call | **geteilt, persistent** |
| Agent-Authoring | ja (Rolle `admin`/`agent:write`) | nein | **nein** |
| Agent-Quelle | lokal (Settings + Markdown) | On-Demand-Fetch + Inline | lokal read-only + optional Fetch |
| Default-Agenten | alle konfigurierten | `build`, `plan` | alle (read-only) |
| Skills | lokal verfügbar | nicht verfügbar (MVP) | lokal read-only |
| MCP | lokale Definitionen | lokale Definitionen + Agent-Filter | lokale Definitionen + Agent-Filter |
| Tools | voller Katalog | Leaf-Profil (eingeschränkt) | voll/konfigurierbar |
| Session-Cleanup | nein | ja (ephemer) | nein |
| Identität | User/Admin | Caller-Token (delegiert) | Service oder delegierter User |
| Hauptnutzen | Orchestrierung, Authoring, interaktive Arbeit | parallele, unabhängige Teilaufgaben | mehrere Worker auf gemeinsamem Workspace |

---

## 2. Server Mode (`ENGINE_MODE=server`)

### Zweck

Control Plane: Orchestrator, Workflow-Editor, interaktive Sessions, Authoring
von Agenten und Workflows. Der Server hält den Workspace und den Kontext.

### Agent-Config-Quellen

Beim Start und bei `reloadConfig()`:

1. Globales `settings.json` (`~/.config/opencode/settings.json`) → `agents[]`
2. Projekt-`settings.json` (`<workspace>/settings.json`) → `agents[]`,
   sofern `engine.settings.workspace-enabled=true`
3. Globale Markdown-Agenten: `~/.config/opencode/agents/*.md`
4. Projekt-Markdown-Agenten: `.opencode/agents/*.md` im Workspace und in
   Elternverzeichnissen
5. Baseline: `build` und `plan` werden immer ergänzt, falls sie fehlen

Die zusammengeführte Agent-Liste landet im `AgentCatalogue`. Die vollständige
Definition ist über `AgentCatalogue.config(name)` und
`GET /api/agent/{name}` verfügbar.

### Agent-Authoring

- `POST /api/agent` – Agent aus Markdown anlegen
- `PUT /api/agent/{name}` – aktualisieren
- `DELETE /api/agent/{name}` – löschen
- Voraussetzung: Rolle `admin` (`requireRole("admin")`) und
  `ENGINE_AGENT_AUTHORING_ENABLED=true`
- Dateien werden nach `~/.config/opencode/agents/<name>.md` geschrieben;
  anschließend `reloadConfig()`
- `GET /api/agent/{name}` liefert `{name, content?, config, hash?, updatedAt?}`

### Workspace und Sessions

- Persistenter Workspace unter `ENGINE_WORKSPACE`
- OIDC-User erhalten ein Unterverzeichnis `<workspace>/<user>` (Ausnahmen:
  `opencode`, `anonymous`, `local` → Basis-Workspace)
- Sessions standardmäßig `mode=workspace`; `mode=ephemeral` ist möglich
- Workspace-Switch über `/api/workspace` (Admin)
- Ownership ist aktuell **verzeichnisbasiert** (`Engine.isOwner`); ein
  explizites `owner`-Feld in `SessionInfo` fehlt noch

### Tools

Der volle Tool-Katalog wird registriert:

- Workspace: `read`, `write`, `edit`, `patch`, `glob`, `grep`, `shell`
- Worktrees: `worktree_create`, `worktree_list`, `worktree_remove`
- Sessions: `session_move`, `session_rename`
- Agenten: `subagent`, `task`
- Skills: `skill`, `skill_list`
- Web: `webfetch`, `websearch`
- MCP: dynamisch pro konfiguriertem Server
- Weitere: `question`, `todowrite`, `models`, A2A-Tools, E-Mail-Tools

Die Agent-Config schränkt ein:

- `tools.allow` / `tools.deny`
- `permissions[]` (action/resource/effect)
- `mcpServers[]` (Namespace-Allow-List)
- `skills.enabled/allow/deny/max`

### Skills

- Quellen: `<workspace>/skill`, `<workspace>/skills`,
  `<workspace>/.opencode/skills`, plus `skills.paths`
- Globaler Filter (`skills.allow/deny/max`) und pro-Agent-Filter
- `skill` lädt den Body on demand, `skill_list` listet Metadaten
- Prompt-Time wird nur der Katalog (ID, Name, Beschreibung) gerendert

### MCP

- Definitionen aus dem `"mcp"`-Block der `settings.json`
- Lokale Server (`type=local`, Command/Env) und Remote-Server
  (`type=remote`, URL/Headers/OAuth)
- Registrierung und Tool-Discovery beim Start (`McpServerManager`)
- Der Agent filtert über `mcpServers` die Namespaces

### Identität und Authz

- OIDC (Keycloak/Auth0) oder Basic/None
- `OidcPrincipalProducer` → `PrincipalContext` (`id`, `name`, `roles`,
  `groups`, `claims`, `accessToken`)
- Agent-Authoring: `admin`
- Andere Admin-Endpunkte: `admin`
- `X-QOC-User`-Header wird auf einigen Endpunkten zusätzlich akzeptiert
  (siehe Abschnitt Identität)

### Status

Vollständig implementiert.

---

## 3. Serverless Mode (`ENGINE_MODE=serverless`)

### Zweck

Stateless **Leaf Worker** für unabhängige, parallelisierbare Teilaufgaben:
Web-Recherche, URL-Extraktion, MCP-Operationen, Batch-Verarbeitung. Kein
geteilter Workspace, keine verschachtelten Subagents.

### Agent-Config-Quellen

Priorität:

1. **Inline-Config** über `qoc_run_agent_with_config`
   - Der Orchestrator sendet Agent-Markdown direkt im MCP-Call
   - Wird über `AgentMarkdownLoader.parseContent` geparst
   - In-Memory als transienter Agent materialisiert
   - Nach dem Call entfernt; kein Dateisystem-Schreibzugriff
2. **Lokaler Katalog**
   - Nur `build` und `plan` (plus global Markdown-Agenten, im Container
     normalerweise leer)
   - Settings-`agents[]` und Projekt-`.opencode/agents` werden im
     Serverless-Modus **ignoriert**
3. **On-Demand-Fetch vom Server**
   - `GET {ENGINE_AGENT_SOURCE_URL}/api/agent/{name}`
   - `Authorization: Bearer <Token des aufrufenden MCP-Clients>`
   - Fallback: `ENGINE_AGENT_SOURCE_TOKEN`
   - Nur wenn der Agent lokal nicht vorhanden ist (`!hasAgent`)
   - Antwort: `{name, content?, config, hash?, updatedAt?}`
   - `config` wird in-memory als transienter Agent materialisiert
   - Nach dem Call: `removeTransientAgent`, kein Cache
4. **Fehler** `agent not available`, wenn kein Fetch möglich ist

Wichtige Konfiguration:

```env
ENGINE_MODE=serverless
ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074   # Registry/Server-Basis, nicht /mcp
ENGINE_AGENT_SOURCE_TOKEN=...                              # optionaler Fallback
```

Nur `config` wird übertragen. **Nicht** übertragen werden:
- MCP-Server-Definitionen (nur die Namespace-Allow-List `mcpServers`)
- Skill-Inhalte (nur der Skill-Filter)
- Workspace-Inhalte, Provider-Secrets, globale Skill-Filter

### Lifecycle eines Calls

```
Client/Orchestrator
  └─ POST /mcp  (Bearer <user-token>)
       └─ qoc_run_agent(agent="chat", prompt=...)
            ├─ Agent lokal? build/plan → ja: direkt
            ├─ sonst: GET {SOURCE}/api/agent/chat (Bearer Caller)
            │    └─ config → defineTransientAgent (in-memory)
            ├─ createSession(mode=ephemeral, retain=false)
            ├─ prompt ausführen
            └─ finally: removeTransientAgent
                 + deleteSession → ephemeres Verzeichnis löschen
```

### Workspace und Sessions

- Ziel: `mode=ephemeral` pro Call
- `createSession(..., "ephemeral", false)` legt ein Session-Verzeichnis unter
  `ENGINE_SESSION_TEMP_DIR` an
- `deleteSession` entfernt es wieder
- Kein geteilter Workspace
- **Aktueller Stand:** Der MCP-Default ist noch `workspace`; die Umstellung auf
  `ephemeral` und das automatische Session-Cleanup sind geplant

### Tools (Leaf-Profil)

Ziel-Policy:

| Erlaubt | Verboten |
|---|---|
| `webfetch`, `websearch` | `worktree_create/list/remove` |
| `read`, `write`, `edit`, `patch`, `glob`, `grep` (nur Scratch) | `question` |
| `todowrite` | `subagent`, `task` |
| `shell` (nur Scratch) | `session_move`, `session_rename` |
| MCP-Tools (Namespace-gefiltert) | `skill`, `skill_list` (MVP) |
| `list_mcp_resources`, `read_mcp_resource` | `models` |

Konfiguration (geplant):

```env
ENGINE_SERVERLESS_TOOL_PROFILE=leaf
ENGINE_SERVERLESS_ALLOW_TOOLS=...
ENGINE_SERVERLESS_DENY_TOOLS=...
```

**Aktueller Stand:** Die Leaf-Policy ist noch nicht implementiert; der
Serverless-Container registriert derzeit denselben Tool-Katalog wie der Server.

### Skills

Im MVP **nicht** unterstützt:

- Keine Skill-Dateien im leeren Serverless-Workspace
- Kein Remote-Skill-Loading
- Der Orchestrator inlined die benötigten Instruktionen im Prompt

Später optional:
- `GET /api/skill/{id}` für Bodies + Supporting Files
- Skill-Katalog im Agent-Fetch
- `SkillContentSource`-Port im Engine, lazy Laden mit Per-Call-Cache
- virtuelle `baseDirectory` (`remote://skill/<id>`), keine Server-Pfade rendern

### MCP

- Definitionen kommen aus der **lokalen** Serverless-Konfiguration
  (`settings.json` `"mcp"` oder Deployment-Env)
- Der Agent filtert über `mcpServers` die Namespaces
- Fehlt ein Namespace: Warnung in `_meta.warnings` (geplant), optional
  `ENGINE_SERVERLESS_MCP_STRICT=true` → Fehler
- Keine MCP-Secrets vom Server

### Identität

- Der Serverless-Container validiert den Bearer-Token des MCP-Callers
- `principalContext.get().id()` wird als Session-User verwendet
- Der Raw-Token wird an den Agent-Fetch weitergereicht
- Der Worker agiert **als der Caller**, nicht als eigener Service-User
- MCP-Server-Aufrufe selbst erhalten aktuell keinen User-Token (globale
  Credentials); per-User-Credentials sind offen

### Limits und Parallelität

- `ENGINE_TURN_MAX_WALL_MINUTES=10`
- `ENGINE_MAX_CONCURRENT_PROMPTS`: 1 für Lambda/Cloud Run, 4 für lokale
  Paralleltests
- Horizontale Skalierung über Instanzen
- Mehrere synchrone MCP-Calls parallel vom Orchestrator
- Ergebnis: Text + optional `structuredContent` + `_meta`
  (Tokens, Finish, Warnungen, `agentCreated`, `agentDeleted`)

### Status

- On-Demand-Agent-Fetch: **implementiert**
- Inline-`qoc_run_agent_with_config`: **implementiert**
- Default `build`/`plan`: **implementiert**
- Leaf-Tool-Policy, Default `ephemeral`, automatisches Session-Cleanup,
  MCP-Warnungen: **geplant**

---

## 4. Worker Mode (`ENGINE_ROLE=worker`, geplant)

### Zweck

Mehrere Worker auf einem **gemeinsamen, persistenten Workspace** betreiben,
ohne dass sie Agent-Konfigurationen schreiben dürfen. Der Editor/Orchestrator
bleibt der einzige Schreiber.

### Abgrenzung zu Serverless

| | Serverless | Worker |
|---|---|---|
| Workspace | ephemer, kein Shared Space | geteilt, persistent |
| Agent-Quelle | On-Demand-Fetch + Inline | read-only Dateien + optional Fetch |
| Skills | keine (MVP) | lokal read-only |
| Default-Agenten | `build`, `plan` | alle konfigurierten |
| Einsatz | parallele I/O-Teilaufgaben | dauerhafte Worker auf gemeinsamen Dateien |

`serverless` ist daher **nicht** der richtige Modus für einen VM-Worker mit
Shared Workspace.

### Agent-Config-Quellen

1. `<shared-workspace>/.opencode/agents/*.md` (read-only)
2. `~/.config/opencode/agents/*.md` (read-only)
3. `settings.json` → `agents[]` (read-only)
4. optional: `GET {ENGINE_AGENT_SOURCE_URL}/api/agent/{name}`

Für den optionalen Fetch muss die aktuelle Kopplung an
`engine.isServerlessMode()` entkoppelt werden: Fetch immer erlauben, wenn eine
Source-URL gesetzt ist.

### Authoring

- `ENGINE_AGENT_AUTHORING_ENABLED=false`
- `POST/PUT/DELETE /api/agent` → 403
- Dateisystem: Agent-Verzeichnisse read-only gemountet
- Nur der Editor/Orchestrator (Control Plane) schreibt
- Empfohlene Scopes: Worker nur `agent:read` + `mcp:run`

### Workspace und Sessions

- `ENGINE_WORKSPACE_MODE=shared`
- Persistenter Workspace
- Sessions bleiben erhalten
- Worker dürfen Aufgaben-Dateien bearbeiten, aber nicht die Agent-Konfiguration

### Tools

- Standardmäßig voller Workspace-Tool-Katalog
- Konfigurierbar über eine Worker-Policy
- `shell` und Datei-Tools sind auf dem geteilten Workspace möglich

### Skills und MCP

- Skills aus dem geteilten Workspace (read-only) und globalen Quellen
- MCP-Definitionen lokal; Agent filtert Namespaces
- Keine Authoring-Rechte für Skills/Agenten

### Identität und exklusives Schreibrecht

Drei Ebenen:

1. **API-Authz:** `agent:write` nur für Editor/Admin; Worker `agent:read`
2. **Dateisystem:** Agent-Config read-only für Worker
3. **Single-Writer + Versionierung:** Editor schreibt, Worker lesen mit
   `hash`/`updatedAt`/ETag

Zusätzlich: Audit-Log für Agent-/Workflow-Writes und Admin-Sperre für andere
mutierende Endpunkte (`/api/config/reload`, Workspace-Switch, Session-Move).

### Status

Nicht implementiert. Erfordert:
- `ENGINE_ROLE=server|worker`
- `ENGINE_WORKSPACE_MODE=shared|ephemeral`
- Entkopplung des Agent-Fetch von `ENGINE_MODE`
- Authz-Scopes und read-only Mounts

---

## 5. Vergleichsmatrix (Details)

| Dimension | Server | Serverless | Worker |
|---|---|---|---|
| Agent aus `settings.json` | ja | nein | ja (read-only) |
| Agent aus Projekt-`.opencode/agents` | ja | nein | ja (read-only) |
| Agent aus globalem Markdown | ja | ja (meist leer) | ja (read-only) |
| On-Demand-Fetch | nein | ja | optional (geplant) |
| Inline-Config | über API/Datei | `qoc_run_agent_with_config` | über API/Datei |
| Authoring-API | ja (admin) | nein (403) | nein (403) |
| Default-Agenten | alle | build/plan | alle |
| Skills | lokal | keine (MVP) | lokal read-only |
| MCP-Definitionen | lokal | lokal | lokal |
| MCP-Namespace-Filter | ja | ja | ja |
| Workspace | persistent | ephemer | geteilt persistent |
| Shell | voll | nur Scratch (geplant) | voll |
| Subagents | ja | nein (geplant) | ja/konfigurierbar |
| Session-Cleanup | nein | ja (geplant) | nein |
| Identität | User/Admin | Caller-delegiert | Service/delegiert |

---

## 6. Identität und User-Weitergabe

### Ist-Zustand

1. **Browser:** OIDC Authorization Code + PKCE; Token in `sessionStorage`;
   `Authorization: Bearer` bei API/SSE.
2. **Quarkus:** OIDC validiert das JWT.
3. **`OidcPrincipalProducer`:** baut `Principal` aus `sub`,
   `preferred_username`, Rollen (Keycloak `realm_access.roles`, Auth0
   `roles`/namespaced Claims), Gruppen und Claims; speichert ihn im
   `PrincipalContext` (Request-scoped), inklusive Raw-Token.
4. **`HttpApi`:**
   - `POST /api/session`: `X-QOC-User` zuerst, sonst Principal-ID
   - `POST /api/run`: nur `X-QOC-User`, Fallback `anonymous`
   - `GET /api/session`/`{id}`: Principal-ID für Ownership
   - Agent-Authoring: `admin`
5. **`Engine.createSession`:** User-ID → `<workspace>/<user>` (außer
   `opencode`/`anonymous`/`local`).
6. **`Engine.isOwner`:** prüft nur den Verzeichnis-Prefix.
7. **`McpServerResource`:** User = Principal-ID; Raw-Token wird an den
   Agent-Fetch weitergereicht.
8. **`ToolContext`:** enthält **keinen** Principal.
9. **A2A:** Basic oder Trusted-Proxy-Header.

### Bekannte Lücken

- `X-QOC-User` ist im OIDC-Modus spoofbar; `POST /api/run` nutzt nicht den
  authentifizierten Principal.
- `SessionInfo` hat kein `owner`-Feld; Ownership hängt am Verzeichnisnamen.
- Rollen werden kaum durchgesetzt (nur `admin`); keine `agent:read`/
  `skill:read`/`mcp:run`-Scopes.
- Kein Principal im `ToolContext`; async Tools können den Request-Scope
  verlieren.
- MCP-Server erhalten keinen per-User-Token; globale Credentials.
- Raw-Token wird nur für den Agent-Fetch weitergereicht.

### Zielbild

- `sub` als stabiler Owner-Key; `SessionInfo.owner` + Migration
- `X-QOC-User` im OIDC-Modus ignorieren; Admin-Impersonation nur explizit
  (`act-as`) und auditierbar
- Scopes: `qoc-admin`, `qoc-editor` (`agent:write`), `qoc-user`
  (`agent:read`, `mcp:run`, eigene Sessions), `qoc-worker` (`agent:read`,
  `mcp:run`)
- Principal im `ToolContext` / `ExecutionIdentity`
- Token Exchange (RFC 8693) für ausgehende Aufrufe (`aud=mcp-server`,
  `aud=qoc-engine`), `act`-Claim für Delegation
- Per-User-Credentials verschlüsselt, kein globaler MCP-Token
- User-getriggerte Worker: delegierter User-Token; Systemtasks: Service-Identity

---

## 7. Entscheidungen (Serverless MVP)

| # | Frage | Entscheidung |
|---|---|---|
| 1 | `shell` im Worker | lokal im ephemeren Scratch erlaubt |
| 2 | `write`/`edit` im Scratch | ja |
| 3 | `subagent`/`task` | deny |
| 4 | `max-concurrent-prompts` | konfigurierbar: 1 (Lambda/Cloud Run) oder 4 (lokal/VM) |
| 5 | MCP-Namespace fehlt | Warnung in `_meta.warnings`, optional Strict-Mode |

---

## 8. Implementierungsplan

### Serverless MVP

1. Leaf-Tool-Policy in `Engine.filterToolSnapshot` (Serverless-Modus)
2. Default `mode=ephemeral` in `McpServerResource.toolsCall`
3. Session-Cleanup nach dem Call (`sessionMode=new`, `retain=false`)
4. `_meta.warnings` für fehlende MCP-Namespaces; optional Strict-Mode
5. `skills.filter` in `EngineConfig.agentToJson` serialisieren (Bugfix)
6. Compose: `ENGINE_SERVERLESS_TOOL_PROFILE=leaf`,
   `ENGINE_SESSION_TEMP_DIR=/tmp/qoc-sessions`,
   `ENGINE_MAX_CONCURRENT_PROMPTS=1|4`

### Worker Mode

1. `ENGINE_ROLE=server|worker` einführen
2. `ENGINE_WORKSPACE_MODE=shared|ephemeral`
3. Agent-Fetch unabhängig von `ENGINE_MODE` (nur Source-URL entscheidet)
4. Authoring per Authz + read-only Mounts erzwingen
5. Scopes `agent:read`/`agent:write`/`mcp:run` durchsetzen
6. `SessionInfo.owner` + DB-Migration
7. Principal in `ToolContext` propagieren

### Tests

- Serverless: Leaf-Policy, Ephemeral-Cleanup, parallele Calls,
  MCP-Filter/Warnungen, Inline-Config ohne Dateisystem
- Worker: Read-only Agent-Config, 403 bei Authoring, gemeinsamer Workspace
- Identität: kein `X-QOC-User`-Spoofing im OIDC-Modus, Owner-Feld,
  Rollen/Scopes
- JaCoCo ≥ 80 %

---

## 9. Offene Punkte

- Fire-and-forget-Async-Jobs oder synchrone MCP-Calls mit Orchestrator-Parallelität?
- Artefakt-Handoff: strukturiertes Output vs. Object Storage vs. MCP
- Remote-Skills: Katalog im Agent-Fetch oder separater Endpoint?
- MCP-Config: strikt deployment-lokal (empfohlen) oder sanitisiert vom Server?
- Scope-Namen und Rollenmodell final festlegen
- Worker-Rolle: eigene `ENGINE_ROLE`-Achse oder nur Capability-Flags?

---

## 10. Subagent-Delegation

Der Entwurf für die Delegation an lokale, Serverless- und Server-Worker über
das `subagent`-Tool – inklusive Worker-Pool, Routing, MCP-Proxy-Varianten und
Cloud-Topologien – steht in `guides/Subagent-Delegation.md`.


## 11. Registry-Rolle (optional)

QOC kann ohne Ausführung als **Agent-Registry** betrieben werden:

```env
ENGINE_MODE=server
ENGINE_ROLE=registry
ENGINE_AGENT_AUTHORING_ENABLED=true
```

- Agent-CRUD/Read (`/api/agent`) bleibt verfügbar.
- Ausführung (`/api/run`, Sessions, MCP-Tool-Calls, A2A) liefert HTTP 503.
- Serverless- und VM-Worker nutzen `ENGINE_AGENT_SOURCE_URL` auf die Registry.

Details: `guides/Agent-Registry.md`.
