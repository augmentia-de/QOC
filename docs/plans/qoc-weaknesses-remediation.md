# Implementierungsplan: Behebung der gefundenen QOC-Schwächen (P0–P2)

> **Ziel:** Die im Vergleich zu OpenCode gefundenen QOC-Schwächen (Sicherheit,
> Semantik, Performance, Produktreife) in priorisierter Reihenfolge beheben.
>
> **Bezug:**
> - `qoc/docs/QOC-vs-OpenCode-Extensions.md`
> - `qoc/docs/Features.md`
> - Code unter `qoc/engine/`, `qoc/workflow-runner/`, `qoc/quarkus/`, `qoc/reactflow/`
>
> **Status:** Plan (2026-11-18)  
> **Prioritäten:** P0 = sofort, P1 = nächste Iteration, P2 = nach Stabilisierung

---

## Umsetzungsstatus (2026-11-18)

Bereits umgesetzt:
- P0-1 Ownership für Workflow-Runs (`WorkflowResource` list/status/resume).
- P0-2 Stabile HITL-Approval-IDs (kein `System.identityHashCode` mehr).
- P0-3 Geschachtelte Subworkflow-Ausführung (`DefaultNodeDispatcher`) mit Tiefenlimit.
- P0-4 Fork/Join als strukturelle Knoten dokumentiert und validiert.
- P1-1 Journal `lastSeq(...)` statt Full-History-Scans in Hot Paths.
- P1-2 `SessionStore.listByTitlePrefix(...)` für Run-Scan-Optimierung.
- P1-4 MCP-Server bleibt Tool-only; OAuth-Stubs sind dokumentiert, nicht implementiert.
- P1-10 Editor-Palette um `guard` und `inject` ergänzt (16 Node-Typen).
- P1-3 Fork/Join-Formvalidierung in `GraphCompiler`.
- P1-5 Opt-in Strict Structured Output (`qoc.structured-output.strict` / `QOC_STRUCTURED_OUTPUT_STRICT`).
- P1-7 Tool-Outputs auf Dateisystem (`FileSystemToolOutputStore`) statt DB-Blobs für SQL/Postgres.
- P1-9 Resilience-Metriken in `/api/metrics` (`resilienceMetrics()`).

Noch offen: P1-9 Metriken, P2-1 bis P2-5.

---

## 0. Leitprinzipien

1. **Keine stillen Semantikänderungen.** Wenn `subworkflow`, `fork` oder `guard`
   heute nur strukturell/expandierend arbeiten, wird entweder wirklich ausgeführt
   oder die UI/Doku sagt das explizit.
2. **Sicherheit vor Komfort.** Workflow-Runs sind Sessions; Ownership-Checks sind
   für Run-Liste, Status und Resume verpflichtend.
3. **Durable IDs statt Prozessidentität.** IDs für HITL und Replay müssen aus
   Session/Run/Node abgeleitet werden, nicht aus `System.identityHashCode`.
4. **Performance als Feature.** Journal-Scans, Run-Scans und DB-Blobs werden als
   technische Schulden behandelt, nicht als Detail.
5. **Konzepte bleiben Konzepte.** Runtime-Adaptation und MCP-OAuth
   werden nicht als fertig verkauft, bis Code und Tests existieren. Temporal
   wird nicht benötigt.

---

## 1. P0 – Sicherheit und Kernsemantik

### P0-1: Ownership für Workflow-Runs schließen

**Problem:** `WorkflowResource.listRuns`, `runStatus` und `resumeRun` prüfen keine
Ownership, obwohl Workflow-Runs normale Sessions sind.

**Ziel:** Nur Admins oder Owner dürfen Runs listen, lesen oder resumen.

**Umsetzung:**
- `listRuns`: Runs nach `SessionInfo.ownerId()` filtern, analog zu
  `listWorkflows()`.
- `runStatus`: `accessGuard.requireAccess(runId)` oder
  `requireWorkflowAccess(ownerId)` vor Replay.
- `resumeRun`: vor `executor().resume(runId)` Ownership prüfen; bei fremdem Run
  `403`.
- Optional: Owner aus `WorkflowRunSnapshot`/`SessionInfo` in `WorkflowRunJournal`
  exponieren, damit keine zweite Session-Abfrage nötig ist.

**Dateien:**
- `quarkus/.../http/WorkflowResource.java`
- `quarkus/.../http/SessionAccessGuard.java`
- `workflow-runner/.../runner/WorkflowRunJournal.java`

**Tests:**
- Quarkus-Integrationstest: User A kann Run von User B nicht lesen/resumen.
- Admin kann alle Runs lesen.
- Anonymous/leerer Principal verhält sich wie bisher (shared mode).

**Akzeptanz:**
- `GET /api/workflows/runs`, `GET /api/workflows/runs/{runId}`,
  `POST /api/workflows/runs/{runId}/resume` liefern `403` für fremde Runs.

---

### P0-2: Stabile HITL-Approval-IDs

**Problem:** `DefaultNodeDispatcher` erzeugt
`"hitl:" + currentNodeId + ":" + System.identityHashCode(ctx)`. Die ID ist
nicht stabil über Neustarts und widerspricht dem Kommentar „deterministic“.

**Ziel:** Approval-ID aus Run/Session/Node ableiten, nicht aus Objektidentität.

**Umsetzung:**
- `WorkflowStepProviderImpl`/`WorkflowContext` Run-ID oder Session-ID in
  `NodeDispatcher`/`DefaultNodeDispatcher` verfügbar machen.
- Approval-ID als `hitl:<sessionId>:<nodeId>` oder `hitl:<runId>:<nodeId>`.
- Bei Replay muss dieselbe ID rekonstruiert werden.
- `DurableApprovalSource.resolve(...)` bleibt tokenbasiert; nur die intern
  referenzierte Approval-ID wird stabil.

**Dateien:**
- `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- `workflow-runner/.../runner/WorkflowStepProviderImpl.java`
- `workflow-runner/.../runner/WorkflowContext.java`
- `workflow-runner/.../runner/WorkflowExecutor.java`

**Tests:**
- Unit-Test: gleicher Run/Node ergibt gleiche `approvalId`.
- Resume-Test: Approval-ID nach Replay identisch.

**Akzeptanz:**
- Kein `System.identityHashCode(ctx)` mehr in Approval-Pfaden.

---

### P0-3: Subworkflow-Semantik klären und umsetzen

**Problem:** `subworkflow` expandiert den Kindgraphen, führt ihn aber nicht aus.
UI/Doku suggerieren Workflow-Ausführung.

**Option A (empfohlen kurzfristig): Semantik explizit machen**
- Node als „Subworkflow-Referenz/Expansion“ dokumentieren.
- Keine Ausführung behaupten.
- Validierung prüft Existenz des Kindworkflows.

**Option B (mittelfristig): echte geschachtelte Ausführung**
- `DefaultNodeDispatcher` ruft `WorkflowExecutor.executeRun(subWf, input, principal)` auf.
- Child-Run-ID und Child-Session-ID journaled.
- Fehler/ Pause des Kindlaufs propagieren in den Parent-Run.
- Rekursions-/Tiefenlimit und Cycle-Schutz.

**Dateien:**
- `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- `workflow-runner/.../runner/SubWorkflowExpander.java`
- `workflow-runner/.../runner/WorkflowExecutor.java`
- `reactflow/src/nodeTypes.tsx`, `reactflow/src/EditorPage.tsx`
- `docs/Features.md`, `docs/engineering/Workflow.md`

**Tests:**
- Unit: fehlender Kindworkflow liefert deterministischen Fehler.
- Integration: erfolgreicher Kindlauf schreibt Output in Parent-State.
- Pause: Kindlauf `paused` pausiert Parent durabel.

**Akzeptanz:**
- Entweder echte Ausführung mit Tests oder UI/Doku ohne Ausführungsbehauptung.
- Keine Mischform in öffentlicher Doku.

---

### P0-4: Fork/Join-Semantik klären

**Problem:** `fork`/`join` sind strukturelle Marker, keine parallele Ausführung.

**Umsetzung:**
- Kurzfristig: Doku/UI als „strukturelle Branch-/Merge-Knoten“ bezeichnen.
- Mittelfristig: entweder echte Fan-out/Fan-in im Step-Loop oder Entfernung des
  Eindrucks paralleler Ausführung.
- `join` sollte Outputs aller eingehenden Nodes deterministisch zusammenführen
  (bereits teilweise).

**Dateien:**
- `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- `docs/Features.md`, `docs/engineering/Workflow.md`, `reactflow/src/nodeTypes.tsx`

**Akzeptanz:**
- Keine Formulierung „parallele Ausführung“ ohne Implementierung.

---

## 2. P1 – Produktreife, Performance und Integration

### P1-1: Journal-Performance (`history().size()`)

**Problem:** `Engine.java` ruft `journal.history(id, Seq.zero()).size()` mehrfach
pro Prompt; jeder Aufruf lädt die gesamte Historie.

**Umsetzung:**
- `Journal.lastSeq(session)` / `nextSeq(session)` ergänzen oder
  `event_sequence`-Tabelle nutzen wie OC.
- `history()` nur für Replay/Projektion.
- ConversationStore `nextSeq` aus Journal ableiten statt `max(seq)`.

**Dateien:**
- `engine/.../core/port/store/Journal.java`
- `engine/.../adapter/sql/SqlJournal.java`
- `engine/.../adapter/sqlite/SqliteJournal.java`
- `engine/.../http/Engine.java`

**Tests:**
- Performance-Test/Benchmark: 1000 Events, keine Full-Scan bei Prompt.
- Regression: Replay und Sequenznummern bleiben korrekt.

**Akzeptanz:**
- Kein `history(...).size()` im Hot Path.

---

### P1-2: Run-Scan und Pagination

**Problem:** `WorkflowRunJournal.scan(limit)` lädt alle Sessions, filtert nach
Titelpräfix und replayt jede Session. Bei vielen Runs O(n) und hohe Latenz.

**Umsetzung:**
- DB-Query mit `title LIKE 'workflow:%'` und `ORDER BY updated DESC LIMIT ?`.
- Optional Owner-Filter in SQL.
- Cursor/Pagination statt nur `limit`.

**Dateien:**
- `workflow-runner/.../runner/WorkflowRunJournal.java`
- `engine/.../core/port/store/SessionReader.java`
- SQL/SQLite-Adapter

**Tests:**
- 1000 Sessions, Scan bleibt unter akzeptabler Zeit.
- Pagination liefert stabile Reihenfolge.

**Akzeptanz:**
- `scan(limit)` macht keine Volltabellen-Replay für alle Sessions.

---

### P1-3: Graph-Validierung schärfen

**Problem:** Doku behauptet „genau ein `end`“, Code verlangt mindestens einen.
Fork/Join werden nicht auf Struktur geprüft.

**Umsetzung:**
- Entscheidung: entweder genau ein `end` erzwingen (empfohlen für klare
  Workflows) oder Doku an Code anpassen.
- Validierung für `fork` (mindestens zwei ausgehende Edges) und `join`
  (mindestens zwei eingehende Edges).
- `subworkflow`-Existenzprüfung optional mit Store-Kontext.

**Dateien:**
- `workflow-runner/.../validation/GraphCompiler.java`
- `docs/Features.md`
- `reactflow/src` (Validierungsfeedback)

**Tests:**
- Unit-Tests für 0/1/mehrere `end`-Nodes.
- Unit-Tests für degenerierte fork/join.

**Akzeptanz:**
- Doku und Code stimmen überein.

---

### P1-4: MCP-Server erweitern oder begrenzen

**Problem:** MCP-Server bietet nur zwei Tools; keine Resources/Prompts/Sessions;
OAuth-Endpoints sind Stubs.

**Umsetzung:**
- Kurzfristig: OpenAPI/Doku klar als „Tool-only MCP Server“.
- Mittelfristig:
  - Resources für Workflow-Definitionen/Runs.
  - Prompts für Agent-Templates.
  - OAuth oder explizite Deaktivierung der Stubs.
- Registry-/Serverless-Rollen weiter härten.

**Dateien:**
- `quarkus/.../http/McpServerResource.java`
- `docs/guides/*`, `docs/security.md`

**Tests:**
- Contract-Tests für `initialize`, `tools/list`, `tools/call`.
- 401/403 Tests bei OAuth-Mode.

**Akzeptanz:**
- Keine `501`-Stubs in produktivem Default-Pfad oder klare Dokumentation.

---

### P1-5: Strukturierte Outputs härten

**Problem:** `submit_result` arbeitet non-strict; Abweichungen werden reaktiv
erkannt und können Retries auslösen.

**Umsetzung:**
- Strict Mode als opt-in Flag implementieren (Weg B aus
  `docs/plans/strict-structured-outputs.md`).
- `additionalProperties(false)`, alle Felder required, `.strict(true)` nur für
  OpenAI/Azure.
- Validierung beim Start: Schema strict-kompatibel?
- Metriken: Retry-Rate, Schema-Verletzungen.

**Dateien:**
- `engine/.../core/json/Schema.java`
- `quarkus/.../http/LangChain4jProviderAdapter.java`
- `docs/CONFIG.md`

**Tests:**
- Unit: strict-Schema-Transformation.
- Integration mit echtem Key (CI-Secret) optional.

**Akzeptanz:**
- Flag `qoc.structured-output.strict` wirkt nachweisbar.

---

### P1-6: Guardrails erweitern oder ehrlich begrenzen

**Problem:** `Features.md`/Marketing spricht von deklarativen Regeln/CDI-SPI,
Code hat nur externen Webhook.

**Umsetzung:**
- Kurzfristig: Doku/UI klar als „Webhook Guardrail“.
- Mittelfristig:
  - Lokale Regel-Definitionen (z. B. Tool-Allow/Deny, Parameter-Patterns).
  - CDI-SPI für in-process Guardrails.
  - Audit-Log für block/rewrite/redact.
- Egress-Sicherheit des Webhooks beibehalten (Host/IP/TLS).

**Dateien:**
- `quarkus/.../guardrail/GuardrailHooks.java`
- `quarkus/.../guardrail/GuardrailRegistry.java`
- `quarkus/.../guardrail/WebhookClient.java`
- `docs/webhook.md`, `docs/security.md`

**Tests:**
- Unit: block/rewrite/redact/ask.
- Security-Tests für nicht erlaubte Hosts/IPs.

**Akzeptanz:**
- Keine Behauptung deklarativer Regeln ohne Implementierung.

---

### P1-7: DB-Blobs und Tool-Outputs auf Dateisystem/Objektstorage

**Problem:** `tool_output` und `snapshot_file` speichern große Inhalte in der DB;
OC nutzt Dateisystem/Git.

**Umsetzung:**
- Tool-Outputs wie OC auf FS mit Retention und Inline-Limit.
- Snapshots weiterhin Git-basiert oder Objektstorage.
- DB speichert nur Metadaten/Hash/URI.

**Dateien:**
- `engine/.../adapter/sql/*ToolOutput*`
- `engine/.../adapter/sql/*Snapshot*`
- `engine/.../core/port/store/ToolOutputStore.java`
- `engine/.../core/port/store/SnapshotStore.java`

**Tests:**
- Migration/Compatibility-Test.
- Large payload test.

**Akzeptanz:**
- Keine großen Blobs im Hot Path der DB.

---

### P1-8: Tool-Result-Cache TTL/Invalidierung

**Problem:** `tool_result_cache` hat keine TTL/Invalidierung; Cache kann veralten.

**Umsetzung:**
- TTL-Feld und Cleanup-Job.
- Invalidierung bei Write/Edit/Patch/Shell-Side-Effects.
- Konfigurierbare Cache-Policy.

**Dateien:**
- `engine/.../runtime/tool/runtime/ToolRuntime.java`
- `engine/.../core/port/store/ToolResultCache.java`
- SQL/SQLite-Adapter
- Migration

**Tests:**
- Cache-Hit/Expiry.
- Side-effect invalidation.

**Akzeptanz:**
- Cache kann nicht unbegrenzt veraltete Ergebnisse liefern.

---

### P1-9: Resilience-Metriken und Observability

**Problem:** Preflight, Triage, Loop-Detector existieren, aber Nutzen/Health
sind schwer beobachtbar.

**Umsetzung:**
- Metriken für Preflight-Failures, Triage-Attempts, Loop-Detections,
  Webhook-Blocks, Verify-on-Stop-Failures.
- Health/Readiness berücksichtigt Preflight.
- SSE/OTel-Events für Guard-Entscheidungen.

**Dateien:**
- `engine/.../runtime/resilience/*`
- `quarkus/.../http/*`
- `docs/optimization.md`, `docs/observability` (falls vorhanden)

**Tests:**
- Unit: Metrik-Inkrement.
- Integration: SSE-Event bei block.

**Akzeptanz:**
- Betrieb kann Guard-/Resilience-Ereignisse sehen.

---

### P1-10: Editor-Palette und Node-Typen angleichen

**Problem:** Backend unterstützt 16 Typen, Editor registriert 14 (`guard`, `inject`
fehlen).

**Umsetzung:**
- Entweder `guard`/`inject` im Editor implementieren oder aus Backend/Docs
  entfernen.
- UI-Validierung gegen `GraphCompiler.VALID_NODE_TYPES`.

**Dateien:**
- `reactflow/src/nodeRegistry.ts`
- `reactflow/src/EditorPage.tsx`
- `reactflow/src/nodeTypes.tsx`

**Tests:**
- Frontend-Tests für alle Node-Typen.

**Akzeptanz:**
- Keine Diskrepanz zwischen Backend und Editor.

---

## 3. P2 – Konzepte und zukünftige Fähigkeiten

### P2-1: Runtime-Adaptation (nur Spezifikation bis Code existiert)

**Problem:** Dokumentiert, aber nicht implementiert.

**Umsetzung:**
- Spec finalisieren (Inputs, Outputs, Trigger).
- Erst dann `RequirementChecker`, `AdaptationPlan`, `RuntimePlan`, `PlanValidator`
  implementieren.
- Tests für Plan-Validierung.

**Dateien:**
- `docs/next/runtime-plan-adaptation.md`
- `docs/plans/runtime-plan-adaptation.md`
- neues Paket `engine/.../runtime/adaptation/`

**Akzeptanz:**
- Kein Code-Claim vor Implementierung; Spec bleibt als Plan markiert.

---

### P2-2: Temporal-Integration (nicht benötigt / out of scope)

Temporal ist eine externe, verteilte Workflow-Orchestrierungsplattform. QOC hat
bereits journalbasierte Recovery, Resume und Checkpoints; eine Temporal-
Integration ist daher optional und aktuell nicht erforderlich. Sie bleibt
Konzept/Dokumentation und wird nicht als Feature gezählt.

---

### P2-3: MCP-OAuth-Server

**Problem:** Stubs liefern `501`.

**Umsetzung:**
- Entweder vollständigen OAuth-Flow implementieren (Authorize, Callback, Token)
  oder Endpoints entfernen/deaktivieren und Doku anpassen.
- Security-Tests für Token-Validierung.

**Dateien:**
- `quarkus/.../http/McpServerResource.java`
- `quarkus/.../http/security/*`

**Akzeptanz:**
- Kein halber OAuth-Modus im Default.

---

### P2-4: A2A-Härtung

**Problem:** A2A-Server ist opt-in; AuthZ/Routing vorhanden, aber Reife und
Tests ausbaufähig.

**Umsetzung:**
- Agent-Card-Discovery und Task-Lifecycle-Tests.
- AuthN/AuthZ für A2A-Endpoints standardisieren.
- Fehlerpfade (Timeout, Cancel, Retry) testen.

**Dateien:**
- `quarkus/.../http/a2a/*`
- `engine/.../runtime/tool/a2a/*`

**Akzeptanz:**
- A2A kann als stabile Integration dokumentiert werden.

---

### P2-5: Agent-Store-Konsistenz und Cleanup

**Problem:** File/DB-Agent-Store mit Cache; generierte Agenten können
ansammeln.

**Umsetzung:**
- Cleanup-Policy für `gen-...`-Agenten.
- Cache-Invalidierung bei externen Änderungen.
- Ownership/Concurrency-Tests.

**Dateien:**
- `workflow-runner/.../store/AgentStore.java`
- `workflow-runner/.../store/AgentStorePort.java`
- `quarkus/.../http/AgentStoreResource.java`

**Akzeptanz:**
- Keine veralteten generierten Agenten nach Workflow-Löschung.

---

## 4. Querschnitt: Tests und Qualität

Für jede P0/P1-Aufgabe gilt:
- Unit-Tests für Kernlogik.
- Integrationstests für HTTP/DB, soweit Maven/Quarkus-Testinfrastruktur vorhanden.
- ArchUnit-Regeln bei neuen Paketen (`engine.core` bleibt frei von Quarkus/Jackson/CDI/HTTP).
- JaCoCo ≥ 80 % Instructions für betroffene Module vor Merge.
- `mvn test` grün.

---

## 5. Empfohlene Reihenfolge

1. **Woche 1:** P0-1, P0-2, P0-3 (Option A), P0-4 Dokumentation.
2. **Woche 2:** P1-1, P1-2, P1-3.
3. **Woche 3:** P1-4, P1-5, P1-6.
4. **Woche 4:** P1-7, P1-8, P1-9, P1-10.
5. **Danach:** P2 nach Bedarf.

---

## 6. Definition of Done

Eine Schwäche gilt als behoben, wenn:
- Code und Tests vorhanden sind,
- Doku/Features konsistent sind,
- keine überzogenen Claims mehr bestehen,
- `mvn test` für betroffene Module grün ist,
- und die Änderung in `qoc/docs/QOC-vs-OpenCode-Extensions.md` als erledigt
  markiert werden kann.
