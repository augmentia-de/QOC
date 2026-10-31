# QOC – Erweiterungen gegenüber OpenCode

> **Status:** 2026-10-31  
> **Zweck:** Übersicht, welche Fähigkeiten QOC über den OpenCode-Referenzcode
> (`oc/`) hinaus bietet. QOC ist eine Java/Quarkus-Neuimplementierung der
> OpenCode-Engine; `oc/` dient nur als Referenz und wird nie geändert.

QOC ist eine **Java/Quarkus-Neuimplementierung der OpenCode-Engine**. Darüber
hinaus enthält QOC eine Reihe von Erweiterungen, die über OpenCode hinausgehen.

---

## 1. Plattform & Architektur

- **Reines Java 21 / Quarkus 3.40** statt Bun/TypeScript; Engine-Kern
  (`engine.core`) ohne Quarkus, CDI, Jackson oder HTTP (Ports/Adapter).
- **Drei Laufzeitformen:** JVM, Native Image (GraalVM), Container; systemd-Unit,
  Docker (JVM + Native).
- **Zwei Datenbanken:** SQLite (WAL) oder PostgreSQL (Flyway-Migrationen,
  Dev Services).
- **ArchUnit-Regeln** und JaCoCo ≥ 80 % als Quality-Gate.

## 2. Sicherheit & Identität (über OpenCode hinaus)

- **Authentifizierungsmodi:** `none`, `basic`, `oidc` (Keycloak), `proxy`
  (Trusted Reverse Proxy), `mcp-oauth`.
- **Principal-Propagation** über CDI `PrincipalContext` + `ToolContext.metadata()`.
- **Trusted-Proxy-CIDRs**, Egress-Allowlists für Webhook und A2A.
- **Drei Schichten:** OS-Hardening, Engine-Policy (deny by default),
  Shell-Scanner.
- **Webhook-Guardrail:** optionaler externer Policy-Dienst (PII, Security,
  Logging) als CDI-SPI.
- **Audit-Logging** für Permission-, Tool- und Guardrail-Entscheidungen.
- **OS-based Security Guide** (`docs/guides/OS-based-security.md`).

## 3. A2A (Agent-to-Agent) – Client und Server

- **Client:** externe A2A-Agenten als Tools, Agent-Card-Discovery mit TTL-Cache,
  Auth, Egress-Guard.
- **Server:** opt-in JSON-RPC-Server (`engine.a2a.server.enabled`), Agent Card,
  SSE-Streaming, Task-Lifecycle, Sessions als bounded LRU.
- **DataPart/FilePart, Skill-Enrichment, Task-Input-Extraktion.**
- Discovery-Endpoint `/api/a2a/agents`, UI.

## 4. MCP (Client + Server)

- **Client:** MCP-Tools und -Resources als Tools, OAuth-Support.
- **Server:** QOC als MCP-Server (`qoc_run_agent`), One-Shot-Tasks,
  Session-Reuse.
- MCP-Resource-Tools (`list_mcp_resources`, `read_mcp_resource`).

## 5. Skills

- Progressive Disclosure, 8 KiB Body-Limit.
- **BM25-Filter** (`topK`, `minScore`, `thresholdCount`, `alwaysInclude`).
- **Per-Agent-Konfiguration:** `allow`/`deny` werden zu Permission-Regeln
  kompiliert, Prompt-Modi `none`/`core`/`top-k`/`all`, `max`.
- Hidden Skills via `disable-model-invocation`, `skill_list`.
- Debug-Logging für Skill-Loading/-Nutzung.

## 6. Session Engine

- Inbox mit `STEER`/`QUEUE`, Compaction (automatisch + manuell),
  Snapshots/Revert.
- **Subagents** (depth limit, concurrency semaphore, background tasks).
- Parallele Tool-Calls, Fork, Location-Switch, Worktrees.
- Event-Sourcing (Journal), SSE-Streaming, Live-Events.
- **Session-Retention** (konfigurierbar, SQLite/PostgreSQL).
- **Ephemeral One-Shot Sessions** mit Temp-Verzeichnis – Tools werden pro
  Session auf ein separates Verzeichnis umgeleitet, Session + Verzeichnis
  werden am Ende gelöscht.

## 7. Tools

- Dateisystem (read/write/edit/patch/glob/grep/list), Shell (optional PTY),
  Web (fetch, Firecrawl), Skills, Subagents, Todos, Questions, Worktrees,
  MCP, A2A.
- **Remote Shell** (spec-only, noch nicht implementiert).

## 8. Kontext & Instructions

- Automatische `AGENTS.md`/`CLAUDE.md`/`SYSTEM.md`/`APPEND_SYSTEM.md`-Discovery.
- Context-Size-Estimation, Compaction-Strategien.

## 9. Provider

- OpenAI-kompatibel, SSE-Streaming, Token-Budgets, Prompt-Cache,
  Retry/Classification, Rotating LLM-Trace.

## 10. Laufzeit-Agenten

- **Runtime-Agent-Erstellung** (`agent_create`-Tool): Agenten können zur
  Laufzeit definiert werden (Prompt, Tools, Skills, Mode) und als Subagent
  genutzt werden.
- **E-Mail-Tool** (`email_fetch`, `email_read`, `email_send`): IMAP/SMTP-basierter
  E-Mail-Client mit Ordnerlisting, Suche, Lesen (inkl. Anhänge als Base64) und
  Versand (Text/HTML + Anhänge). Zugangsdaten über Config/Env.
- Agent-Import aus OpenCode + Agent-Modi (primary/subagent/all).

## 11. Operations & Observability

- Docker (JVM/Native), Compose (A2A/MCP-Interop), systemd, Nginx/TLS.
- OpenTelemetry, Rotating LLM-Trace, Health/Ready-Endpoints.
- GCP-VM-Deployment (native Binary).

## 12. Quality

- ArchUnit, JaCoCo ≥ 80 %, E2E-Tests (Skill-Loading, Orchestration,
  Compaction, A2A/MCP).
- Docker-basierte Interop-Suite (Konzept).

## 13. Prototypen / Spec-only

- **Decision Gate** (LangChain4j `DecisionModel`) als isolierter Prototyp in
  `decision-gate/` (QOC pinnt 1.19.3, Decision-API braucht 1.21+).
- Remote Shell (download/upload) spec-only.
- `infratest`-Modul (PostgreSQL-Deployment-Tests).

---

## Zusammenfassung

QOC erweitert OpenCode um: **Java/Quarkus-Runtime, A2A-Client/Server,
MCP-Server-Modus, Webhook-Guardrail, Skills-Filterung & Per-Agent-Skills,
Ephemeral One-Shot Sessions, Runtime-Agent-Erstellung,
Principal-Propagation/Security-Layer, PostgreSQL-Persistenz,
Compaction/Subagent-Orchestrierung, OpenTelemetry/Tracing und native
Container-Deployments.**
