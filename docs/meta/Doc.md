# Dokumentations-Inventar und Konsolidierungsempfehlung

> **Status:** 2026-11-04
> **Zweck:** Übersicht aller Markdown-Dateien in `docs/` mit Kategorisierung,
> Redundanzanalyse und Konsolidierungsempfehlung. Die operative Spezifikation
> unter `spec/` ist von Konsolidierung ausgenommen.

---

## 1. Kategorisierung

### 1.1 Original-Spezifikation (`spec/`) — NICHT anfassen

Die operative, sprachneutrale Spezifikation. Quelle der Wahrheit.

| Datei/Ordner | Inhalt |
|---|---|
| `spec/README.md` | Einstieg, Lesereihenfolge, Modulplan |
| `spec/module-plan.md` | Module, Ports, Abhängigkeiten, M0–M14 |
| `spec/java-packages.md` | Java-Pakete, ArchUnit-Regeln |
| `spec/CONVENTIONS.md` | Schreibregeln für Specs |
| `spec/INVARIANTS.md` | Invarianten-Register (I-*, P-*, W-*, etc.) |
| `spec/learnings.md` | ⚠️ Falsch benannt – enthält Learnings, nicht Backlog |
| `spec/session-engine/` (14 Dateien) | Session-Engine: Typen, Events, Zustände, Algorithmen, Provider, Fehler, Invarianten |
| `spec/tools/` (10 Dateien) | Tool-Contract, Filesystem, Bash, MCP, Subagent, Remote Shell (spec-only), MCP-Server |
| `spec/permissions/` (6 Dateien) | Permission-Typen, Evaluation, Effect-Matrix, Invarianten, Java-Impl |
| `spec/provider/` (7 Dateien) | Provider-Typen, Event-Stream, Klassifikation, Catalog, Invarianten, Java-Impl |
| `spec/persistence/` (3 Dateien) | JDBC/Flyway, Data-Ownership |
| `spec/betrieb/` (5 Dateien) | HTTP-Streaming, Skills, Credentials, OIDC/Auth0 (spec-only) |
| `spec/qualitaet/` (4 Dateien) | Tests, Architektur-Pipeline, Implementierbarkeit, Docker-E2E |
| `spec/agents/` (5 Dateien) | Agent-Config, Tool-Restrictions, MCP-Scoping, Workflows |

### 1.2 Top-Level Operative Dokumente

| Datei | Inhalt | Status |
|---|---|---|
| `ROADMAP.md` | M0–M14 + M15+ Meilensteine, Java-Impl-Phasen | Aktiv |
| `STATUS.md` | Implementiert/Partial/Spec-only pro Bereich | Aktiv |
| `INDEX.md` | Role-based Einstieg, Spec-Map, Guides, Deployment | Aktiv |
| `FUTURE-WORK.md` | Zentrale Übersicht geplanter/nicht-implementierter Teile | Neu |
| `README.md` | Dokumentationsrollen, Einstieg | Aktiv |
| `open-questions.md` | Entscheidungsregister (meist entschieden) | Referenz |
| `optimization.md` | Optimierungskatalog | Working |

### 1.3 Security & Deployment

| Datei/Ordner | Inhalt | Status |
|---|---|---|
| `security.md` | Sicherheitsmodell, OAuth-Zielbild, Auth0-Plan | Uncommitted, aktiv |
| `cicd.md` | CI/CD-Pipeline, Betriebsmodi, Deployment-Strategie | Uncommitted, aktiv |
| `enterprise.md` | Enterprise-Readiness (4 Säulen) | Working |
| `enterprise.md` | Erweiterungen gegenüber OpenCode | Reference |
| `deployment/` (8 Dateien) | Lokal Docker, GCP Native, GCP Auth0, AWS ECS, Lambda, Security-Hardening, Free-Tier | Aktiv |

### 1.4 Guides

| Datei | Inhalt | Status |
|---|---|---|
| `guides/a2a.md` | A2A Client/Server-Konfiguration | Implemented |
| `guides/deployment-modes.md` | Server/Serverless/Worker-Modi | Design-Dokument |
| `optimization.md` | Ideen/Skizzen-Katalog | Working |
| `guides/langchain4j.md` | LangChain4j Integration Guide | Implemented |
| `guides/LoadAgentConfig.md` | Agent-Config Reload | Implemented |
| `guides/OS-based-security.md` | OS-User-basierte Sicherheit | Implemented |
| `guides/deployment-modes.md` | Server/Serverless-Modi (spec-only, superseded) | Spec-only |
| `guides/Subagent-Delegation.md` | Worker-Pool, Routing, Cloud-Topologien | Design (nicht implementiert) |
| `guides/a2a.md` | A2A Client/Server | Implemented |

### 1.5 Analyse & Referenz

| Datei/Ordner | Inhalt | Status |
|---|---|---|
| `analysis/overview.md` | System-Übersicht | Reference |
| `analysis/01-use-cases.md` | Use Cases, Gherkin, Akzeptanztests | Reference |
| `analysis/02-data-flow.md` | Datenfluss, Events, Tabellen | Reference |
| `analysis/03-bounded-contexts.md` | Modul-Grenzen, Kopplung | Reference |
| `analysis/04-change-impact.md` | Pitfalls, Guardrails | Reference |
| `analysis/ANALYSIS-ARCHITECTURE-JAVA.md` | Meta-Analyse Java-Architektur | Reference |
| `analysis/a2a.md` | A2A-Integration-Analyse | Reference |
| `analysis/flow-db-service/` (7 Dateien) | Tiefenanalyse DB-Service-Flow | Reference |
| `analysis/learnings.md` | Gemeinsame Learnings (D-01–D-14) | Working |
| `analysis/analysis/learnings/nessy.md` | Learnings aus Nessy | Working |
| `analysis/analysis/learnings/oc-open.md` | Learnings aus OpenCode | Working |
| `analysis/analysis/learnings/oh-my-pi.md` | Learnings aus oh-my-pi | Working |
| `analysis/analysis/learnings/pi.md` | Learnings aus pi coding agent | Working |

### 1.6 Diagramme

| Datei | Inhalt |
|---|---|
| `diagrams/01-system-context.md` bis `11-security-and-mcp.md` | Mermaid-Diagramme: System-Kontext, Architektur, Runtime-Dataflow, Session-Lifecycle, Klassenmodell, Dependencies, Tool-Permission-Chain, Agent-Orchestrator, Persistence, Deployment, Security/MCP |
| `diagrams/README.md` | Diagramm-Index und Legende |

### 1.7 Plans & Issues

| Datei | Inhalt | Status |
|---|---|---|
| `plans/async-approval.md` | Async Approvals (Phase 1+2+3 umgesetzt) | Partial |
| `plans/e2e-security-test.md` | E2E Security Test Plan | Working |
| `issues/qoc-findings.md` | Aktuelle Probleme/Findings | Working |

### 1.8 Top-Level Erweiterungen

| Datei | Inhalt | Status |
|---|---|---|
| `enterprise.md` | Erweiterungen gegenüber OpenCode | Reference |
| `enterprise.md` | Enterprise-Readiness (4 Säulen) | Working |
| `skill-loading.md` | Skill-Discovery, Filtering, per-Agent-Config | Implemented |
| `context-build.md` | Context-Building, Skill-Permission-Gaps | Design draft |
| `skill-loading.md` | Skill-Discovery und Filtering | Implemented |
| `optimization.md` | Prompt-Minimierung, Context-Economy | Design draft |
| `understand.md` | LLM Call Flow & Tool Execution | Design note |
| `webhook.md` | Webhook Guardrail Service | Partial |
| `analysis/learnings/deepseek-harness.md` | Learnings aus deepseek-harness | Working |
| `guides/langchain4j.md` | LangChain4j Einsatz/Ersatzpotenzial | Working |
| `skill-loading.md` | Skill-Loading | Implemented |

---

## 2. Redundanzen

### R1: LangChain4j-Dokumentation (2 Dateien)
- `docs/guides/langchain4j.md` (375 Zeilen) – "Ersatzpotenzial und Abgrenzung"
- `docs/guides/langchain4j.md` (213 Zeilen) – "Integration Guide"
- **Überlappung:** Beide behandeln LangChain4j-Einsatz in QOC.
- **Empfehlung:** Zusammenführen zu `guides/langchain4j.md` (Integration + Abgrenzung).

### R2: Serverless/Deployment-Modi (3 Dateien)
- `docs/guides/deployment-modes.md` – Server/Serverless-Modi (spec-only, superseded)
- `docs/guides/deployment-modes.md` – Server/Serverless/Worker (Design)
- `docs/guides/Subagent-Delegation.md` – Worker-Pool, Routing, Cloud-Topologien (Design)
- **Überlappung:** Alle drei beschreiben Deployment-Modi und Cloud-Topologien.
- **Empfehlung:** Zusammenführen zu `guides/deployment-modes.md` (eine Datei, server-serverless-modes.md entfernen oder als Anhang).

### R3: Learnings (5 Dateien) — ✅ ERLEDIGT
- `docs/analysis/learnings.md` (Hauptdatei + Index)
- `docs/analysis/learnings/` (nessy.md, oc-open.md, oh-my-pi.md, pi.md, deepseek-harness.md)
- **Aktion:** 5 Learnings-Dateien nach `analysis/learnings/` verschoben, Index in `learnings.md` ergänzt.

### R4: Extensions (2 Dateien) — ✅ ERLEDIGT
- `docs/enterprise.md` (erweitert um OC-plus-Inhalte)
- `docs/OC-plus.md` gelöscht, Referenzen aktualisiert.

### R5: Ideen/Skizzen (2 Dateien) — ✅ ERLEDIGT
- `docs/optimization.md` (erweitert um Ideen aus ideas.md)
- `docs/guides/ideas.md` gelöscht, Referenzen aktualisiert.

### R6: Spec-implementation-backlog (falsch benannt) — ✅ ERLEDIGT
- `docs/spec/implementation-backlog.md` → `docs/spec/learnings.md` umbenannt.

### R7: refactor-v2.md (archival) — ✅ ERLEDIGT
- `docs/analysis/refactor/` gelöscht (leer), Referenzen entfernt.

---

## 3. Potentielle TODOs (Spec-only / Partial)

| Datei | Status | Beschreibung |
|---|---|---|
| `spec/tools/08-remote-shell.md` | spec-only | Remote Shell (SSH) – exec/read/write/list/stat implementiert, download/upload fehlen |
| `spec/betrieb/04-oidc-auth0.md` | spec-only | Auth0 OIDC – Claim-Mapping teilweise implementiert |
| `deployment/05-aws-lambda-serverless.md` | spec-only | Lambda-Modus – Skripte vorhanden, Implementierung ausstehend |
| `guides/deployment-modes.md` | spec-only | Server/Serverless-Modi (superseded durch deployment-modes.md) |
| `guides/Subagent-Delegation.md` | spec-only | Worker-Pool, Routing (nicht implementiert) |
| `spec/qualitaet/` | partial | ArchUnit/JaCoCo Gates |
| `plans/async-approval.md` | partial | Async Approvals (Phase 1+2+3 umgesetzt) |
| `webhook.md` | partial | Webhook Guardrail Service |
| `guides/a2a.md` | implemented | A2A Client/Server |
| `context-build.md` | design draft | Context-Building, Skill-Permission-Gaps |
| `optimization.md` | design draft | Prompt-Minimierung (hat Corruption-Marker) |

---

## 4. Konsolidierungsempfehlung

### Sofort (hoher Nutzen, niedriges Risiko)

1. **`guides/langchain4j.md` + `guides/langchain4j.md`** → `guides/langchain4j.md`
   - Integration Guide + Abgrenzung in einer Datei.

2. **`guides/deployment-modes.md`** → In `guides/deployment-modes.md` integrieren
   - server-serverless-modes.md ist als superseded markiert.

3. **`optimization.md`** → In `optimization.md` integrieren
   - Ideen und Optimierungen in einer Datei.

4. **`spec/learnings.md`** → Umbenennen zu `learnings.md` oder nach `analysis/` verschieben
   - Falsch benannt, enthält Learnings.

### Mittelfristig (moderater Aufwand)

5. **Learnings konsolidieren:** `analysis/learnings/nessy.md`, `analysis/learnings/oc-open.md`, `analysis/learnings/oh-my-pi.md`, `analysis/learnings/pi.md`, `analysis/learnings/deepseek-harness.md` → `analysis/learnings/` Ordner oder in `analysis/learnings.md` integrieren.

6. **`enterprise.md` + `enterprise.md`** → Zusammenführen oder `enterprise.md` als Anhang in `enterprise.md`.

7. **`optimization.md`** reparieren (Corruption-Marker `|` entfernen).

### Nicht konsolidieren (Original-Spec)

Die gesamte `spec/`-Struktur bleibt unverändert. Sie ist die operative Spezifikation und Quelle der Wahrheit. Insbesondere:
- `spec/session-engine/`, `spec/tools/`, `spec/permissions/`, `spec/provider/`, `spec/persistence/`, `spec/betrieb/`, `spec/qualitaet/`, `spec/agents/`
- `spec/README.md`, `spec/module-plan.md`, `spec/java-packages.md`, `spec/CONVENTIONS.md`, `spec/INVARIANTS.md`

### Nicht anfassen (Referenz/Archiv)

- `analysis/` (Referenzmaterial, bewusst getrennt)
- `diagrams/` (Architektur-Diagramme)
- `plans/` (zeitlich begrenzte Umsetzungspläne)
- `issues/` (Working Issue Register)
- `tools/` (Dokumentations-Tools)

---

## 5. Zusammenfassung

| Kategorie | Dateien | Konsolidierung? |
|---|---|---|
| Original-Spec (`spec/`) | ~50 Dateien | ❌ Nein (Quelle der Wahrheit) |
| Top-Level Operativ | 7 Dateien | Nein |
| Security & Deployment | 10+ Dateien | Nein (neu strukturiert) |
| Guides | 8 Dateien | ✅ Konsolidiert (R1, R2, R5) |
| Analyse & Referenz | 16 Dateien | ✅ Konsolidiert (R3) |
| Diagramme | 12 Dateien | Nein |
| Plans & Issues | 3 Dateien | Nein (zeitlich begrenzt) |
| Top-Level Erweiterungen | 9 Dateien | ✅ Konsolidiert (R4, R6) |

**Empfohlene Sofortmaßnahmen:** R1, R2, R5, R6 (4 Dateien zusammenführen/umbenennen, niedriges Risiko).
**Mittelfristig:** R3, R4, R7 (Learnings konsolidieren, Extensions zusammenführen).
