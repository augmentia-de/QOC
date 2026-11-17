# QOC – Gesamteinführung und Architektur-Übersicht

> Ablage: `docs/qoc-complete-guide.md` (außerhalb des Git-Repos)
> Sprache: Deutsch
> Version: 2026-11 (Workflow-Version mit Structured Output, RBAC-Plan, In-Memory-Minimal-Modus)

---

## 1. Was ist QOC?

**QOC** (Quarkus OpenCode) ist eine Java-Portierung des OpenCode-Frameworks –
nicht als Kopie, sondern **spec-driven**. Erst Analyse, dann Spezifikation,
dann Code. Die Spezifikation (`qoc/docs/spec/`) ist die fachliche Quelle der
Wahrheit; die Implementierung folgt ihr.

QOC ist ein **konfigurierbarer KI-Agent**, der als Worker in beliebigen
Workflows eingesetzt werden kann. Er unterstützt:

- **Arbitäre Agent-Definitionen** (System-Prompt, Modell, Tools, Guardrails,
  Skills, Permissions, Structured Output).
- **Tools** (Dateisystem, Shell, Web, E-Mail, MCP, Subagents, A2A).
- **MCP-Integration** (als Client und Server).
- **Zwei Betriebsmodi**: kurzlebige Sessions für One-off-Tasks (z. B. MCP)
  und durable Multi-Turn-Sessions für längere Agent-Runs (Kontext,
  Compaction, Resume).

### 1.1 Abstammung von OpenCode

QOC basiert auf der Analyse des OpenCode-Checkouts (`oc/`). OpenCode ist ein
TypeScript/Node.js-Framework mit SQLite-Backend, WAL-Modus, eingebetteter
Datenbank (`~/.local/share/opencode/opencode.db`), Git-Snapshots, Tool-Outputs
auf dem Dateisystem und Markdown-basierten Agent-Definitionen.

QOC übernimmt die **Konzepte** (Session-Engine, Event-Sourcing, Permissions,
Tools, MCP), aber nicht den Code. Die Implementierung ist spec-driven in Java 21
mit Quarkus. Erkenntnisse aus OpenCode (z. B. `event_sequence`-Tabelle statt
`history().size()`, Snapshots in Git statt in der DB, Tool-Outputs auf dem FS)
fließen als Empfehlungen in die Architektur ein.

### 1.2 Kern-Prinzipien

1. **Spec-driven**: Die Spezifikation definiert die Semantik, nicht der Code.
2. **Reine Core-Schicht**: `engine.core` ist reines Java – keine Quarkus-,
   CDI-, Jackson- oder HTTP-Abhängigkeiten. Externe Technik lebt in
   `engine.adapter.*`.
3. **Ersetzbare Adapter**: SQL, HTTP, Prozesse, Dateisystem sind hinter Ports
   gekapselt. Speicher-Backend (Postgres/SQLite/In-Memory) per Konfiguration.
4. **Architekturregeln mit ArchUnit** abgesichert.
5. **Least-Privilege als Default**: Permissions deny-by-default.

---

## 2. Architektur

### 2.1 Schichtung

```
┌─────────────────────────────────────────────────────┐
│  Quarkus Outer Skin (de.augmentia.oc.engine.http)   │
│  REST-Endpoints, OIDC, SessionAccessGuard,           │
│  WorkflowResource, AgentStoreResource, SchemaResource │
├─────────────────────────────────────────────────────┤
│  QocEngine Facade (engine.runtime)                  │
│  runPrompt, createSession, resolveAgentConfig,       │
│  Step, ToolRuntime, ContextBuilder, CompactionService│
├─────────────────────────────────────────────────────┤
│  engine.core (reines Java)                          │
│  Model, Ports, Contracts, Eval, JSON, Schema        │
├─────────────────────────────────────────────────────┤
│  engine.adapter (sql, sqlite, memory, fs, http,     │
│  process, time)                                     │
└─────────────────────────────────────────────────────┘
```

### 2.2 Paketstruktur

| Paket | Rolle |
|---|---|
| `engine.core.model` | Werttypen (SessionId, AgentId, Message, Event, …) |
| `engine.core.port.store` | Storage-Ports (SessionStore, Journal, ConversationStore, …) |
| `engine.core.port.runtime` | Runtime-Ports (Clock, SessionWake, ApprovalSource, …) |
| `engine.core.contract` | Tool, Provider, Step-Kontrakte |
| `engine.core.eval` | Context-Evaluation, OutputBounding, CacheAffinity |
| `engine.core.json` | JSON/Schema-Parser (`JsonSchema.parse`, `JsonValues`) |
| `engine.core.wiring` | `Stores`, `StorageConfig`, `StorageKind` |
| `engine.runtime` | `QocEngine`, `Step`, `ToolRuntime`, `ContextBuilder`, `Coordinator` |
| `engine.runtime.session` | Session-Service, Admission, Delivery, Recovery |
| `engine.runtime.tool` | Tools (fs, shell, web, mcp, subagent, structured) |
| `engine.runtime.workflow` | WorkflowStepProvider, workflow-mode |
| `engine.runtime.compaction` | Auto-Compaction |
| `engine.adapter` | SQL, SQLite, Memory, FS, HTTP, Process, Time |
| `engine.adapter.memory` | In-Memory-Adapter (minimal) |
| `quarkus.http` | REST-Endpoints, Security, WorkflowResource |

### 2.3 Technologien

| Technologie | Version | Verwendung |
|---|---|---|
| Java | 21 | Sprache |
| Quarkus | 3.40.1.1 | Outer Skin, REST, CDI, OIDC |
| LangChain4j | (via Quarkus-Extension) | LLM-Provider-Integration |
| Maven | 3.9+ | Build |
| Jackson | 2.18.2 | JSON-Serialisierung |
| Flyway | (Quarkus-Extension) | DB-Migrationen |
| ArchUnit | (Test) | Architekturregeln |
| JaCoCo | 0.8.12 | Coverage (≥ 80 %) |
| swagger-parser | 2.1.22 | OpenAPI-Import |
| bubblewrap (bwrap) | System | Shell-Sandbox |
| React + Vite + TypeScript + Tailwind | 19 | Workflow-UI |
| ReactFlow | – | Graph-Editor |
| Temporal | 1.40.0 (geplant) | Workflow-Durability |

---

## 3. Session-Engine

### 3.1 Session-Lebenszyklus

1. **Erstellung**: `SessionService.create(location, agent, model, now)` →
   `SessionId` (zufällig, `ses_` + 64 Hex) + `SessionCreated`-Event im Journal.
2. **Execution**: `QocEngine.runPrompt(session, ...)` → Claim erwerben →
   Preflight → Prompt speichern → Context bauen → Step ausführen → Events
   publizieren → Compaction → Claim freigeben.
3. **Resume**: Bei Crash → `recover()` scannt offene Claims und setzt
   Execution fort.
4. **Löschung**: `deleteSession` entfernt Session, Inbox, Conversation,
   Claims, Workspaces, Instructions.

### 3.2 Kern-Komponenten

- **SessionStore / SessionReader**: Session-Metadaten (Agent, Location, Titel,
  Owner, Tokens, Cost).
- **ConversationStore**: Nachrichten-History (append-only, gapless seq).
- **Journal**: Event-Sourcing (Write-Ahead-Log). Pro Aggregat `ArrayList<StoredEvent>`
  + `AtomicLong seq`.
- **ClaimStore**: At most one execution per session (`putIfAbsent`).
- **SessionWorkspaceStore**: Execution-Mode, Workspace-Root, Instance-ID, retain.
- **CompactionService**: Auto-Kompaktierung langer Histories.
- **InstructionStore**: Instruction-Einträge und Blobs (content-addressed).

### 3.3 Event-Sourcing

Events sind die Quelle der Wahrheit. Projektionen (Session, Conversation)
sind Read-Models. Events:
`SessionCreated`, `ExecutionStarted`, `ToolCalled`, `ToolSuccess`,
`ExecutionSucceeded`/`Failed`/`Paused`, `SessionForked`,
`WorkflowRunStarted`, `WorkflowNodeStarted`/`Completed`/`Failed`,
`WorkflowRunFinished`/`Failed`/`Resumed`.

---

## 4. Tools

### 4.1 Eingebaute Tools

| Tool | Beschreibung |
|---|---|
| `read` | Datei/Verzeichnis lesen (mit Artifact-Store für große Outputs) |
| `grep` | Regex-Suche in Dateien |
| `write` / `edit` | Datei schreiben/bearbeiten |
| `bash` / `shell` | Shell-Befehle (sandboxed via bwrap) |
| `glob` | Datei-Pattern-Suche |
| `web_fetch` | HTTP-Fetch |
| `subagent` / `task` | Subagent-Delegation (Child-Session) |
| `submit_result` | Structured Output (JSON Schema) |
| `question` | Frage an den User |
| `email` (imap/smtp) | E-Mail lesen/senden |
| `mcp_*` | MCP-Server-Tools |
| `a2a_*` | A2A-Agenten |

### 4.2 Tool-Runtime

- **Permission-Gate**: Jeder Tool-Call wird geprüft (least-privilege/allow-all).
- **Output-Bounding**: Große Outputs werden trunciert (2.000 Zeilen / 50 KiB),
  der volle Text als Artifact gespeichert.
- **Idempotenz**: Tool-Result-Cache verhindert Doppel-Ausführung bei Retries.
- **Hooks**: `beforeToolCall` / `afterToolCall` für Audit/Transformation.

---

## 5. Permissions & Security

### 5.1 Modi

| Modus | Beschreibung |
|---|---|
| `none` | Kein Login; lokaler Admin (Loopback) |
| `basic` | HTTP Basic Auth (`OPENCODE_SERVER_PASSWORD`) |
| `oidc` | OIDC (Keycloak/Auth0), Rollen aus JWT |
| `proxy` | Trusted-Proxy-Auth (nginx/LB) |
| `mcp_oauth` | OAuth für MCP-Server |

### 5.2 Permission-Default

- `least-privilege` (deny-by-default) oder `allow-all` (OpenCode-kompatibel).
- Ask-Mode: `deny` / `allow` / `interactive`.

### 5.3 Shell-Sandbox

- bwrap (bubblewrap) mit `workspace-write`-Modus.
- In Docker deaktiviert (unprivilegiert) — Grenze aus Mounts.
- Seccomp-Deny-Liste gegen `ptrace`, `mount`, `unshare`, etc.

### 5.4 Container-Hardening

Read-only Root-FS, targeted Mounts, tmpfs, `cap_drop ALL`,
`no-new-privileges`, `pids_limit`, non-root UID 1000.

---

## 6. Provider

- **HTTP-Provider**: OpenAI-kompatibel (`/chat/completions`).
- **Scripted-Provider**: Für Tests/Dev (kein echter LLM-Call).
- **Modell-Konfiguration**: `engine.model.provider`, `engine.model.id`,
  `engine.turn.max.tokens`.
- **Retry-Policy**: max 3 Versuche, Delays 10s/30s.
- **Preflight-Checks**: DNS, HTTP (konfigurierbar).

---

## 7. Persistence (Storage)

### 7.1 Backends

| Backend | Konfiguration | Verwendung |
|---|---|---|
| Postgres | `engine.storage=postgres` | Multi-Node, Production |
| SQLite | `engine.storage=sqlite` | Single-Node, WAL |
| In-Memory | `engine.storage=memory` | Embedded, Tests, Helper |

### 7.2 Storage-Factory

`StorageFactory.create(config, memoryState, dataSource, clock, fileSystem)` wählt
pro Port das Backend. Per-Port-Overrides möglich (Zwischenstufen).

### 7.3 In-Memory-Minimal-Modus

Nur 6 Stores echt (Session, Conversation, Journal, Claim, Workspace, ToolOutput).
Restliche 5 Ports sind No-Ops. Budget: 300 MiB (nur Tool-Outputs LRU-begrenzt).

### 7.4 Workflow-Storage

`engine.workflow.storage`: `auto` | `file` | `memory` | `postgres` | `sqlite`.
File-basiert: JSON-Dateien in `wf-data/`, Version-Snapshots.

---

## 8. Workflow-Orchestrierung

### 8.1 Datenmodell

- `WorkflowDefinition`: Nodes (id, type, position, data) + Edges (source, target) + Agents.
- Node-Typen: `start`, `end`, `agent`, `decision`, `fork`, `join`, `loop`,
  `hitl`, `subworkflow`, `mcp_call`, `llm_eval`, `llm_summarize`.
- `Agent`: name, version, model, systemPrompt, tools, guardrails, hooks,
  jsonOutput, active, responseSchema.

### 8.2 Execution

- `WorkflowExecutor.executeRun(def, input)` → topologische Sortierung (Kahn)
  → pro Knoten `dispatch()` → `runAgent()` für Agent-Knoten → Journal-Events.
- `runId = sessionId` (keine separate Run-ID).
- Agent-Knoten erzeugen Child-Sessions.
- Resume: `WorkflowExecutor.resume(runId)` → Replay ab Checkpoint.
- HITL: `hitl`-Knoten pausieren; Fortsetzung via Signal.

### 8.3 Structured Output

- `responseSchema` (JSON Schema) an Agent/Node.
- LLM wird via `submit_result`-Tool zur strukturierten Ausgabe gezwungen.
- Ergebnis in `EngineResult.structured()` / `NodeResult.structured`.
- Converter: `JavaRecordToJsonSchema`, `OpenApiToJsonSchema`.
- Endpoints: `/api/schema/from-class`, `/from-openapi`, `/validate`.

### 8.4 3-Säulen-Modell

1. **Deterministische Nodes**: Regeln, Routing, Transformation, 0 Tokens.
2. **Subagent-Nodes**: Isolierte ReAct-Loops, nur Endergebnis zurück.
3. **Context-Agenten**: Kuratierter Gesamtüberblick, optimales Caching.

---

## 9. UI (Workflow-UI)

- React 19 + Vite + TypeScript + Tailwind + ReactFlow.
- Seiten: Dashboard, Workflows (GraphEditor), Agents, Runs, Observe, Testbench,
  Settings, MCP Servers.
- GraphEditor: Drag & Drop, Custom Node Types, Property Panel, Auto-Layout (elkjs).
- SSE-Streaming für Live-Updates.

---

## 10. Deployment

### 10.1 Docker-Varianten

| Service | Profil | Port | Charakteristik |
|---|---|---|---|
| qoc-dev | dev | 8070 | Hot-Reload |
| qoc-local | local | 8071 | Server-Stack (Postgres) |
| qoc-memory | memory | 8082 | In-Memory, keine DB |
| qoc-secure | secure | 8075 | Gehärtet, Least-Privilege |
| qoc-native | native | 8077 | Native-Image (GraalVM) |

### 10.2 Native Image

- Mandrel 25, Quarkus 3.40.
- workflow-runner ist Library (kein Uber-JAR).
- slf4j-api statt slf4j-simple.

---

## 11. Testing & Qualität

- **Unit-Tests**: 950+ (Engine), 11+ (Workflow-Runner).
- **ArchUnit**: Architekturregeln (keine Quarkus-Imports in core, etc.).
- **JaCoCo**: Coverage-Gate ≥ 80 % (aktuell 72 % — vorbestehend).
- **TCK**: Contract-Tests für Storage-Adapter (geplant).

---

## 12. Erweiterungs-Vision

### 12.1 Context Caching

- `CacheAffinity` sendet Session-Headers (bereits).
- `cacheKey`-Feld vorhanden, aber kein Provider-Adapter konsumiert es.
- Geplant: OpenAI `prompt_cache_key`, Anthropic `cache_control`.

### 12.2 RBAC + Systemuser

- Rollen: `admin`, `user`, `system`, `viewer`.
- Systemuser: Service-Account für Automation.
- Ownership: `ownerId` auf Workflow/Agent.
- OIDC-Integration für Workflows (siehe `Workflow-OIDC.md`-Plan).

### 12.3 Weitere

- `transform`-Knoten (Daten-Transformation ohne LLM/MCP).
- Per-Säule-Metriken (OpenTelemetry).
- Subagent-Session-Cleanup (ephemeral).
- Automatische Kuratierung (Summarize-Nodes).
- Temporal-Integration (Workflow-Durability, HITL via Signals).
- Redis-Hot-Tier (Multi-Node).

---

## 13. Offene Fragen

- Multi-Node vs. Single-Node (SQLite)?
- Event-Sourcing-Projektion (Conversation ableitbar)?
- Retention (Events/History)?
- Credentials in Secret-Manager?
- Git-Snapshots vs. content-addressed FS?

---

## 14. Referenzen

| Thema | Pfad |
|---|---|
| Spezifikation | `qoc/docs/spec/` |
| Roadmap | `qoc/docs/ROADMAP.md` |
| Open Questions | `qoc/docs/open-questions.md` |
| Storage-Analyse | `qoc/docs/engineering/Storage-alternative.md` |
| In-Memory | `qoc/docs/engineering/InMemory.md` |
| Multi-Memory | `qoc/docs/engineering/Multi-Memory.md` |
| Workflow | `qoc/docs/engineering/Workflow.md` |
| Structured Output | `qoc/docs/engineering/Structured-Output.md` |
| Native Deployment | `qoc/docs/engineering/Native-Deployment.md` |
| Security Hardening | `qoc/docs/deployment/06-security-hardening.md` |
| CONFIG | `qoc/docs/CONFIG.md` |
| Permission-Interviewer | `qoc/docs/Agent-Permission-Interviewer.md` |
| LinkedIn Follow-up | `2oc/docs/linkedin-followup-2026-11.md` |
| 3-Pillars Vision | `2oc/docs/linkedin-3-pillars-vision.md` |
| Workflow-vs-Agent-Loop | `2oc/docs/qoc-engine-workflow-vs-agent-loop.md` |
