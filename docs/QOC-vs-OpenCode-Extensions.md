# QOC vs. OpenCode – Erweiterungen (priorisiert)

> **Zweck:** Vollständige, priorisierte Inventur der echten QOC-Erweiterungen
> gegenüber dem OpenCode-Referenzcode (`oc/`). Keine Portierungen von OC-Verhalten
> werden als QOC-Erweiterung gezählt.
>
> **Status:** 2026-11-18  
> **QOC-Referenz:** `qoc/` (HEAD `323ffdc7`)  
> **OpenCode-Referenz:** `oc/` (HEAD `863ea41364`)  
> **Methode:** direkte Codeinspektion beider Codebases; OC nur lesend.

---

## 1. Bewertungsschema

| Symbol | Bedeutung |
|---|---|
| **V** | Nutzer-/Produktwert (1 = Nische, 5 = zentraler Differenzierer) |
| **T** | Technische Tiefe (1 = Konfiguration/UI, 5 = Architektur/Runtime) |
| **P0** | Sofort priorisieren / Kern-Differenzierer |
| **P1** | Hoher Wert, aber nach P0 oder mit Vorarbeit |
| **P2** | Konzept, Spezialfall oder erst nach Grundlagen |
| **Status** | `implemented`, `partial`, `concept` |

---

## 2. Executive Summary

QOC ist nicht einfach eine Portierung von OpenCode nach Java. Die echten
Mehrwerte liegen in vier Blöcken:

1. **Workflow-Plattform auf demselben Engine-Loop**  
   ReactFlow-Graphen werden als `LoopMode.WORKFLOW` im selben `QocEngine`-Loop
   ausgeführt, mit Journal-Replay, Resume, Recovery, Versionierung und
   Prompt-zu-Workflow-Generierung.

2. **Enterprise-Betrieb**  
   PostgreSQL neben SQLite/Memory, OIDC/Proxy/RBAC/Ownership, DB-basierte
   Agent- und Workflow-Definitionen und ReactFlow-UI.

3. **Durable Human-in-the-Loop**  
   Async Approvals/Questions mit Token, Deadline, E-Mail/UI, `paused_prompt`,
   Resume und Recovery.

4. **Agent-Ökosystem und Verträge**  
   A2A (Client + Server), MCP-Server-Exposition, strukturierte Outputs,
   Node-Contracts, Context-Budget-Gates und SDLC-Graph-Invarianten.

Wichtige Korrektur gegenüber älteren Feature-Listen: QOC hat **keine
deklarative Guardrail-Regel-Engine**. `GuardrailHooks` ruft einen externen
Webhook auf und kann blockieren, umschreiben oder redigieren. Ebenso sind
`subworkflow`- und `fork`-Nodes heute **strukturell/expandierend**, aber keine
parallele oder geschachtelte Workflow-Ausführung.

---

## 3. Prioritätsübersicht

| Prio | Extensions | Kernnutzen |
|---|---|---|
| **P0** | Workflow-Runner/Loop, durable Runs, Workflow-Generierung, OIDC/RBAC/Ownership, durable HITL, PostgreSQL, A2A | Produkt- und Enterprise-Differenzierer |
| **P1** | MCP-Server, strukturierte Outputs, Node-Contracts/Context-Budget, Graph-Validierung/SDLC, Guardrail-Webhook, Resilience, Tool-Cache, erweitertes Toolset, Agent-Store, ReactFlow-UI, Classify | Nutzbarkeit, Robustheit, Integration |
| **P2** | Runtime-Adaptation (Konzept), MCP-OAuth-Server (Stub), Temporal (Konzept) | Zukünftige, noch nicht produktive Fähigkeiten |

---

## 4. Detaillierte Erweiterungen

### E01 – Workflow-Runner als zweiter Loop-Modus

- **V:** 5 / **T:** 5 / **Prio:** P0 / **Status:** implemented (mit Teilschritten)
- **OC:** nur Agent-Loop, Subagents, ACP, MCP-Client.
- **QOC:** `QocEngine.runPrompt(...)` wechselt per `workflowStepProvider != null && workflowContext != null`
  auf `LoopMode.WORKFLOW`; `WorkflowStepProviderImpl` liefert pro Aufruf genau einen Node-Schritt.
- **Evidenz:** `engine/.../runtime/QocEngine.java:42-44`, `workflow-runner/.../runner/WorkflowStepProviderImpl.java`,
  `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- **Nodes:** 16 Backend-Typen (`agent`, `context_agent_executor`, `decision`, `start`, `end`,
  `fork`, `join`, `loop`, `hitl`, `subworkflow`, `compact`, `mcp_call`, `rest_call`, `classify`,
  `guard`, `inject`). Der ReactFlow-Editor registriert derzeit 14 Typen; `guard`/`inject` sind
  Backend-Typen, aber nicht in der Editor-Palette.
- **Einschränkung:** `subworkflow` expandiert den Kindgraphen, führt ihn aber nicht als
  geschachtelten Run aus; `fork`/`join` sind strukturell und nicht parallel.

### E02 – Durable Workflow-Runs (Journal, Resume, Recovery, Versioning)

- **V:** 5 / **T:** 5 / **Prio:** P0 / **Status:** implemented
- **OC:** Event-Log, Claims und Inbox existieren, aber keine Workflow-Run-Semantik.
- **QOC:** Ein Run ist eine normale Session mit Titel `workflow:<id>`; Zustand liegt in
  `WorkflowRun*`-Events. `WorkflowRunJournal.load(...)` replayt Node-Outputs, State, `currentNodeId`,
  Child-Session-IDs, Resume-Count und Status. `WorkflowExecutor.resume(...)` lädt die passende
  Definitionsversion über `definitionVersion`/`contentHash`, gibt einen stale Claim frei und setzt
  am letzten erfolgreichen Node fort. `WorkflowRecovery` scannt bis zu 200 Runs.
- **Evidenz:** `workflow-runner/.../runner/WorkflowRunJournal.java`,
  `workflow-runner/.../runner/WorkflowExecutor.java:148-203`,
  `engine/.../core/model/SessionEvent.java` (WorkflowRun*-Events)
- **Besonderheit:** bewusst **keine `workflow_run`-Tabelle**; Definition und Version sind getrennt
  vom Run-State.

### E03 – Prompt-zu-Workflow-Generierung

- **V:** 4 / **T:** 4 / **Prio:** P0 / **Status:** implemented
- **OC:** keine Workflow-Generierung.
- **QOC:** `WorkflowGenerator.generate(...)` orchestriert Prompt → LLM → Fences strippen → Steps
  parsen → Graph bauen → validieren → Agenten persistieren → speichern. Agenten erhalten
  `gen-<workflowId>-<agentId>`-IDs und werden im globalen Agent-Store gespeichert.
- **Evidenz:** `workflow-runner/.../generate/WorkflowGenerator.java`,
  `workflow-runner/.../generate/WorkflowPlanParser.java`,
  `workflow-runner/.../generate/WorkflowBuilder.java`,
  `quarkus/.../http/WorkflowResource.java:146-172`
- **Schalter:** `ENGINE_WORKFLOW_GENERATE_ENABLED` (Default aus).

### E04 – PostgreSQL als Backend-Option

- **V:** 4 / **T:** 4 / **Prio:** P0 / **Status:** implemented
- **OC:** ausschließlich SQLite (`bun:sqlite`/`node:sqlite`/workerd), keine Netzwerk-DB.
- **QOC:** `StorageKind` erlaubt pro Port `MEMORY`, `SQLITE`, `POSTGRES`; `StorageFactory` wählt
  Adapter; Flyway-Migrationen V1–V12 für Postgres und SQLite; Postgres nutzt `jsonb`,
  `timestamptz`, `bigserial`.
- **Evidenz:** `engine/.../core/wiring/StorageKind.java`,
  `engine/.../adapter/StorageFactory.java`,
  `engine/src/main/resources/db/migration/V1__engine.sql` … `V12__mcp_server.sql`,
  `engine/src/main/resources/db/migration-sqlite/...`
- **Hinweis:** OC-Referenz nutzt WAL-SQLite; QOC bietet zusätzlich Multi-Node-fähiges Postgres.

### E05 – OIDC, Trusted Proxy, RBAC und Ownership

- **V:** 5 / **T:** 4 / **Prio:** P0 / **Status:** implemented (konfigurationsabhängig)
- **OC:** Basic Auth / lokale Passwort-Authentifizierung, keine OIDC/RBAC-Ownership-Semantik
  für Workflows.
- **QOC:** `OidcPrincipalProducer` (JWT/OIDC), `TrustedProxyFilter`, `RolesAllowedFilter`,
  `SecurityConfig` (NONE/BASIC/OIDC/PROXY/MCP_OAUTH), `SessionAccessGuard` mit Owner-/Admin-Checks,
  Workflow-/Agent-Ownership in REST-Ressourcen.
- **Evidenz:** `quarkus/.../http/security/OidcPrincipalProducer.java`,
  `quarkus/.../http/security/TrustedProxyFilter.java`,
  `quarkus/.../http/security/RolesAllowedFilter.java`,
  `quarkus/.../http/security/SecurityConfig.java`,
  `quarkus/.../http/SessionAccessGuard.java`,
  `quarkus/.../http/WorkflowResource.java:78-137`
- **Lücke:** `WorkflowResource` prüft Ownership bei Definitionen, aber nicht konsistent bei
  `/workflows/runs`, `/workflows/runs/{runId}` und `/resume`. Das ist ein Sicherheits-/Konsistenzgap,
  keine fertige Erweiterung.

### E06 – Durable async Approvals und Questions

- **V:** 5 / **T:** 4 / **Prio:** P0 / **Status:** implemented
- **OC:** In-Memory `Permission`-Requests mit Deferred-Future; gespeicherte Approvals als
  `permission`-Tabelle. Keine E-Mail-Reply-Links oder pausierte Sessions.
- **QOC:** `DurableApprovalSource` persistiert `pending_approval` mit Single-Use-Token, Deadline,
  Status und sendet über `NotificationChannel` (E-Mail). `DurableQuestionSource` macht dasselbe
  für Fragen. `WebApprovalSource` kombiniert In-Memory-Future mit DB-Persistenz. Workflow-HITL
  pausiert durabel via `NodeResult.paused(...)`.
- **Evidenz:** `quarkus/.../http/DurableApprovalSource.java`,
  `quarkus/.../http/DurableQuestionSource.java`,
  `quarkus/.../http/WebApprovalSource.java`,
  `quarkus/.../http/HttpApi.java:69-82`,
  `engine/src/main/resources/db/migration/V3__pending_approvals.sql`,
  `engine/src/main/resources/db/migration/V8__question_requests.sql`
- **Caveat:** HITL-`approvalId` verwendet `System.identityHashCode(ctx)` und ist damit nicht stabil
  über Prozessneustarts; die Pause selbst ist durabel, aber die ID-Stabilität ist fragil.

### E07 – A2A (Agent-to-Agent)

- **V:** 4 / **T:** 4 / **Prio:** P0 / **Status:** implemented
- **OC:** kein A2A-Client/Server.
- **QOC:** `A2ATool`/`A2AToolProvider` binden externe Agenten als Tools ein (Agent-Card-Cache,
  Discovery). `QocAgentExecutor`, `A2AAgentCardProducer`, `A2ARouteGate` und `A2ASessionRegistry`
  stellen einen opt-in JSON-RPC-A2A-Server bereit.
- **Evidenz:** `engine/.../runtime/tool/a2a/A2ATool.java`,
  `engine/.../runtime/tool/a2a/A2AToolProvider.java`,
  `quarkus/.../http/a2a/QocAgentExecutor.java`,
  `quarkus/.../http/a2a/A2AAgentCardProducer.java`
- **UI:** `reactflow/src/pages/A2APage.tsx`.

### E08 – MCP-Server-Exposition von QOC

- **V:** 4 / **T:** 3 / **Prio:** P1 / **Status:** partial
- **OC:** MCP-Client und MCP-Management-API, aber kein QOC-MCP-Server für Agent-Ausführung.
- **QOC:** `McpServerResource` bietet JSON-RPC `initialize`, `tools/list`, `tools/call` mit den
  Tools `qoc_run_agent` und `qoc_run_agent_with_config`, inkl. transienter Agenten, strukturiertem
  Output und Registry-/Serverless-Rollen.
- **Evidenz:** `quarkus/.../http/McpServerResource.java`,
  `quarkus/src/main/resources/application.properties` (`engine.mcp.server.*`)
- **Grenzen:** nur Tools, keine Resources/Prompts/Sessions; OAuth-Endpoints
  `/mcp/authorize` und `/mcp/callback` sind Stubs (`501 Not Implemented`).

### E09 – Strukturierte Outputs

- **V:** 4 / **T:** 4 / **Prio:** P1 / **Status:** implemented (non-strict; strict als offener Plan)
- **OC:** keine entsprechende `submit_result`-Mechanik gefunden.
- **QOC:** LLM wird über synthetisches Tool `submit_result` gezwungen, ein Ergebnis zu liefern;
  `EngineResult.structured()`/`NodeResult.structured` und
  `StructuredOutputConverter.toMap(...)`. Schema-Konverter für Java-Records, Java-Source,
  OpenAPI und YAML; Endpoints `/api/schema/from-class`, `/from-openapi`, `/validate`.
- **Evidenz:** `docs/engineering/Structured-Output.md`,
  `workflow-runner/.../runner/StructuredOutputConverter.java`,
  `workflow-runner/.../runner/JavaRecordToJsonSchema.java`,
  `workflow-runner/.../runner/OpenApiToJsonSchema.java`,
  `workflow-runner/.../runner/YamlToJsonSchema.java`,
  `quarkus/.../http/McpServerResource.java:292-300`
- **Offen:** Strict Mode (Constrained Decoding) ist geplant, aber nicht implementiert
  (`docs/plans/strict-structured-outputs.md`).

### E10 – Node-Contracts, Context-Budget und Context-Policies

- **V:** 4 / **T:** 4 / **Prio:** P1 / **Status:** implemented
- **OC:** keine Workflow-Node-Contracts.
- **QOC:** `ContextAssembler` mit `FULL`/`FOCUSED`/`ARTIFACTS`/`CUSTOM`; `ContextBudgetGate`
  wirft `WorkflowBudgetExceededException`; `NodeContractValidator` prüft `outputContract`
  (type, required, additionalProperties, enum, bounds, items). Template-Resolver unterstützt
  `${node.output.field}` und `${state.key}` mit Strict-Modus.
- **Evidenz:** `workflow-runner/.../runner/ContextAssembler.java`,
  `workflow-runner/.../runner/ContextBudgetGate.java`,
  `workflow-runner/.../runner/NodeContractValidator.java`,
  `workflow-runner/.../runner/TemplateResolver.java`
- **Nutzen:** reduziert Context-Überlauf und macht Node-Datenfluss prüfbar.

### E11 – Graph-Validierung und SDLC-Invarianten

- **V:** 3 / **T:** 4 / **Prio:** P1 / **Status:** implemented
- **OC:** keine Workflow-Graphen.
- **QOC:** `GraphCompiler` prüft Node-Typen, IDs, Edges, Agent-Refs, Templates, Zyklen,
  Budget-Binding auf Zyklen (SDW-03) und Gate-Bypass (SDW-07). `validateTemplatesOnly(...)`
  erlaubt Draft-Speicherung.
- **Evidenz:** `workflow-runner/.../validation/GraphCompiler.java`,
  `docs/engineering/Workflow-SDLC.md`
- **Korrektur:** Code verlangt mindestens einen `end`-Node, nicht exakt einen; Fork/Join sind
  strukturell, nicht parallele Ausführung.

### E12 – Externer Guardrail-Webhook

- **V:** 3 / **T:** 3 / **Prio:** P1 / **Status:** implemented
- **OC:** Plugin-Hooks, aber keine Webhook-basierte Tool-Guardrail.
- **QOC:** `GuardrailHooks` (before/after Tool-Call) ruft `WebhookClient` auf und unterstützt
  `allow`, `block`, `deny`, `ask`, `redact`, `replace`, `rewrite`, Shadow-Mode und
  Fail-Open/Closed. Agent-Config trägt `guardrailsInput`/`guardrailsOutput`.
- **Evidenz:** `quarkus/.../guardrail/GuardrailHooks.java`,
  `quarkus/.../guardrail/WebhookClient.java`,
  `quarkus/.../guardrail/GuardrailRegistry.java`
- **Korrektur:** Es gibt keine deklarative Regel-Engine und kein CDI-SPI für lokale Guardrails.

### E13 – Resilience-Stack im Agent-Loop

- **V:** 3 / **T:** 3 / **Prio:** P1 / **Status:** implemented
- **OC:** Retry/Backoff und Compaction vorhanden, aber kein Preflight/Triage/Loop-Detector
  in dieser Form.
- **QOC:** Preflight-Checker (DNS/HTTP/TTL), `TriageService`, `LoopDetector`, `WebhookAlerter`,
  `RetryPolicy`, Step-Timeout, Turn-Budgets (Tool-Calls/Tokens/Wall-Time), Verify-on-Stop.
  Verify-on-Stop erlaubt `CONTINUE`/`FAIL`/`WARN` vor Turn-Ende.
- **Evidenz:** `engine/.../runtime/resilience/*`,
  `engine/.../runtime/QocEngine.java:48-183,1204-1230`,
  `engine/.../core/eval/VerifyRule.java`,
  `docs/plans/verify-on-stop-guards.md`

### E14 – Tool-Result-Cache und Idempotenz

- **V:** 3 / **T:** 2 / **Prio:** P1 / **Status:** implemented
- **OC:** kein eigener Tool-Result-Cache-Port.
- **QOC:** `ToolRuntime` nutzt `ToolResultCache` mit `IdempotencyKey`; Cache-Hit liefert
  dekodiertes `ToolOutcome`; Ergebnisse werden nach Ausführung gespeichert. Tabelle
  `tool_result_cache`.
- **Evidenz:** `engine/.../runtime/tool/runtime/ToolRuntime.java:84-170`,
  `engine/src/main/resources/db/migration/V7__tool_result_cache.sql`

### E15 – Erweitertes Toolset

- **V:** 3 / **T:** 2 / **Prio:** P1 / **Status:** implemented
- **OC:** Shell, Read/Write/Edit/Patch, Glob/Grep, WebFetch/Search, Todo, Question, Worktree,
  Subagent, MCP-Resources, Skill.
- **QOC:** zusätzlich `email` (SMTP/IMAP mit Limits), `remote-shell` (SSH), `secure-rest`
  (Allowlist/Credentials), Firecrawl, Check-Tools (`test_runner`, `hash_checker`),
  `agent_create`/`agent_list`, `submit_result`, A2A-Tools, MCP-Call-Tools.
- **Evidenz:** `engine/.../runtime/tool/email/EmailLimits.java`,
  `engine/.../runtime/tool/remote/*`,
  `engine/.../runtime/tool/web/*`,
  `quarkus/.../runtime/tool/agent/AgentCreateTool.java`,
  `workflow-runner/.../runner/CheckTools.java`

### E16 – Globaler Agent-Store und Runtime-Agent-Authoring

- **V:** 4 / **T:** 3 / **Prio:** P1 / **Status:** implemented
- **OC:** Agenten als Markdown/Config-Dateien; keine DB-basierte Agent-SSOT mit Ownership.
- **QOC:** `AgentStore`/`AgentStorePort` persistiert Agenten als JSON (file/sqlite/postgres),
  mit Owner-Filter, CRUD, Import/Export nach OpenCode-Markdown, generierte `gen-...`-Agenten
  und Propagation in die Engine.
- **Evidenz:** `workflow-runner/.../store/AgentStore.java`,
  `workflow-runner/.../store/AgentStorePort.java`,
  `quarkus/.../http/AgentStoreResource.java`,
  `quarkus/.../http/AgentCatalogue.java`

### E17 – ReactFlow-UI und Workflow-Betriebsseiten

- **V:** 4 / **T:** 2 / **Prio:** P1 / **Status:** implemented
- **OC:** TUI/Web-UI, aber keine ReactFlow-Workflow-Editor-Oberfläche.
- **QOC:** `reactflow/` enthält Editor, Dashboard, Workflows, Runs, Agents, HITL, MCP-Servers,
  A2A, Generate, Settings, Observe, Testbench, LLM-Trace.
- **Evidenz:** `reactflow/src/EditorPage.tsx`,
  `reactflow/src/pages/*.tsx`,
  `reactflow/src/services/workflowStore.ts`,
  `quarkus/src/main/resources/META-INF/resources/index.html`

### E18 – Classify-Node (typisiertes Decision-Modell)

- **V:** 3 / **T:** 3 / **Prio:** P1 / **Status:** implemented (externe API-Abhängigkeit)
- **OC:** keine typisierte Decision-Classification.
- **QOC:** `classify`-Node ruft `TypeSafeDecisionClient` auf, normalisiert yes/no/choice/scale,
  berechnet Margin/Low-Margin und schreibt State-Deltas. Guard-Node kann `low_margin`-Pfade
  begrenzen.
- **Evidenz:** `workflow-runner/.../runner/TypeSafeDecisionClient.java`,
  `workflow-runner/.../runner/DefaultNodeDispatcher.java:339-408`,
  `workflow-runner/.../runner/DefaultNodeDispatcher.java:299-331`

### E19 – Workflow-spezifische Event-Typen und Journal-Replay

- **V:** 3 / **T:** 3 / **Prio:** P1 / **Status:** implemented
- **OC:** generisches Event-Log vorhanden; keine Workflow-Run-Events.
- **QOC:** `SessionEvent.WorkflowRunStarted/NodeStarted/NodeCompleted/NodeFailed/RunResumed/
  RunFinished/RunFailed` plus `JournalWorkflowEventSink`; Replay in `WorkflowRunJournal`.
- **Evidenz:** `engine/.../core/model/SessionEvent.java`,
  `workflow-runner/.../runner/JournalWorkflowEventSink.java`,
  `workflow-runner/.../runner/WorkflowRunJournal.java`
- **Hinweis:** Das Event-Sourcing selbst ist keine QOC-Erweiterung; die Workflow-Run-Events sind es.

### E20 – Runtime-Adaptation (Konzept)

- **V:** 4 / **T:** 1 / **Prio:** P2 / **Status:** concept
- **OC:** keine vergleichbare Runtime-Plan-Adaption.
- **QOC:** Dokumentiert `RequirementChecker`, `AdaptationPlan`, `RuntimePlan`, `PlanValidator`,
  aber im Code nicht implementiert.
- **Evidenz:** `docs/next/runtime-plan-adaptation.md`,
  `docs/plans/runtime-plan-adaptation.md`
- **Status:** nicht implementiert; nicht als fertige Erweiterung verkaufen.

### E21 – MCP-OAuth-Server

- **V:** 3 / **T:** 1 / **Prio:** P2 / **Status:** concept/stub
- **OC:** MCP-Client, keine OAuth-Serverrolle.
- **QOC:** Konfiguration `engine.mcp.server.oauth.enabled` und `AuthMode.MCP_OAUTH` existieren,
  aber `/mcp/authorize` und `/mcp/callback` liefern `501 Not Implemented`.
- **Evidenz:** `quarkus/.../http/McpServerResource.java:165-186`

### E22 – Temporal-Integration

- **V:** 2 / **T:** 1 / **Prio:** P2 / **Status:** concept
- **OC:** keine Temporal-Integration.
- **QOC:** Dokumente und Pläne vorhanden, keine Implementierung im Runtime-Code.
- **Evidenz:** `docs/engineering/ReactFlow-temporal.md`,
  `docs/plans/Hybrid.md`

### E23 – Quarkus/JVM-Betriebsmodell

- **V:** 3 / **T:** 2 / **Prio:** P2 / **Status:** implemented
- **OC:** Bun/Node-basierte CLI und Server.
- **QOC:** Quarkus 3.40, JVM/native Image, systemd/Docker-Deployment, Dev Services,
  Profile für Memory/SQLite/Postgres.
- **Evidenz:** `qoc/pom.xml`, `qoc/quarkus/pom.xml`,
  `docs/deployment/*.md`,
  `docs/betrieb/*` (falls vorhanden)
- **Hinweis:** Das ist ein Plattformunterschied, aber für Enterprise-Deployments relevant.

---

## 5. Nicht als QOC-Erweiterung gezählt (Baseline/Parität)

Diese Fähigkeiten sind in OC bereits vorhanden oder werden von QOC portiert; sie sind
keine QOC-spezifischen Mehrwerte:

| Bereich | OC-Evidenz | QOC |
|---|---|---|
| Event-Log, Sequenzen, Claims, Resume-Counter | `packages/core/src/session/event.ts`, `execution.ts`, `inbox.ts` | portiert |
| Session-Inbox (STEER/QUEUE) | `packages/core/src/session/inbox.ts` | portiert |
| Compaction, Revert, Snapshots | `packages/core/src/session/compaction.ts`, `revert.ts` | portiert |
| Skills (Scan, Frontmatter, Disclosure) | `packages/core/src/skill/*` | portiert |
| Subagents/Tasks | `packages/core/src/tool/plugin/subagent.ts` | portiert |
| ACP | `packages/cli/src/commands/commands.ts` (`acp`) | portiert |
| MCP-Client | `packages/core/src/mcp/*`, `packages/server/src/handlers/mcp.ts` | portiert |
| Permissions (deny/ask/allow, saved approvals) | `packages/core/src/permission.ts` | portiert |
| Plugins/Hooks | `packages/core/src/plugin/*` | portiert |
| SQLite/In-Memory-SQLite | `packages/core/src/database/sqlite.*.ts` | portiert (+ Postgres) |
| Basic Auth | OC-Server-Auth | portiert (+ OIDC/Proxy) |

---

## 6. Korrekturen gegenüber `Features.md`

| Stelle | Problem | Empfohlene Korrektur |
|---|---|---|
| `Features.md` §2.4 „16 Node-Typen“ | stimmt für Backend, aber Editor registriert nur 14 | „16 Backend-Node-Typen (14 im Editor; `guard`/`inject` nur Backend)“ |
| `Features.md` §2.4 „genau ein `end`“ | `GraphCompiler` verlangt mindestens einen `end` | „mindestens ein `end`-Node“ |
| `Features.md` §3 „Guardrails: Deklarative Regeln, CDI-SPI“ | nur externer Webhook | „externer Webhook-Guardrail; keine deklarative Regel-Engine“ |
| `Features.md` §4 „Owner-/Access-Guard“ für Runs | `/workflows/runs*` und `/resume` prüfen keine Ownership | Guard für Runs nachziehen oder Formulierung einschränken |
| `Features.md` §2.4 „subworkflow: wird beim Start expandiert“ | keine geschachtelte Ausführung | „expandiert den Kindgraphen, führt ihn aber nicht als geschachtelten Run aus“ |

---

## 7. Empfohlene Umsetzungsreihenfolge

1. **P0 härten:** Ownership-Gap bei Workflow-Runs schließen (E05/E02), HITL-ID-Stabilität
   (E06), Subworkflow-Ausführung oder klare Dokumentation (E01).
2. **P1 produktisieren:** MCP-Server-Ressourcen/Prompts (E08), Strict Structured Output als
   Option (E09), Context-/Contract-Gates in UI sichtbar machen (E10/E11).
3. **P1 Betrieb:** Guardrail-Webhook-Dokumentation und Defaults (E12), Resilience-Metriken
   (E13), Tool-Cache TTL/Invalidierung (E14).
4. **P2 vorbereiten:** Runtime-Adaptation nur als Spezifikation (E20), MCP-OAuth (E21),
   Temporal (E22) erst nach stabiler Workflow-Plattform.

---

## 8. Kurzurteil

Die stärksten QOC-Erweiterungen sind **Workflow-Runner auf demselben Engine-Loop**,
**durable Runs/Resume/Recovery**, **OIDC/RBAC/Ownership**, **PostgreSQL**,
**durable HITL** und **A2A**. Diese fünf (plus Workflow-Generierung) sollten als
QOC-Kernnarrativ und Roadmap-Schwerpunkt behandelt werden. Alles andere ist
wertvoll, aber sekundär oder noch konzeptionell.
