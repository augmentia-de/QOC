# Learnings aus `deepseek-harness` für QOC (code-basiert)

> Quelle: `/home/torsten/dev/my-projects/deepseek-harness` (Cordis-basiertes Agent-Harness, TS/Node).  
> Methode: Analyse der Subsystem-Docs **und** der Quelltypen (`packages/*/src/*.ts`).  
> Ziel: Was QOC aus diesem System übernehmen kann oder sollte — Architektur, Patterns, Features, Invarianten.  
> Status: 2026-11-02 · Analyse, keine Code-Änderung.

---

## 1. Kontext

`deepseek-harness` (dsh) ist ein plugin-basiertes Agent-Harness auf Basis von **Cordis** (plugin framework: Services, Events, reversible Effects). Alles ist Plugin — Modell-Adapter, Tool-Registry, Session-Log, Agent-Loop. QOC ist ein Java/Quarkus-Engine mit festem Kern. Die Learnings sind daher **konzeptionell**: Muster, Datenmodelle, Invarianten, die QOC übernehmen oder nachrüsten kann, ohne die Plugin-Architektur zu kopieren.

---

## 2. Typsystem & Invarianten (code-level)

### 2.1 Branded Types überall
dsh brandet **jede** ID und jeden numerischen Cursor: `SessionId`, `SessionSeq`, `SessionLogOffset`, `FsTargetKey`, `FsVersion`, `ApprovalRequestId`, `WorkspaceId`, `GoalId`, `JobId`, `CredentialRef`, `CredentialKey`, `SpillLocator`, `AttachmentId`, `SubagentPromptRequestId`, `SessionTitleProviderId`, `GoalRef`.  
Numerische Seqs werden mit Validierung gebranded (`SessionSeq`: non-negative safe integer, kein `-0`).  
**QOC-Bezug:** QOC hat `SessionId`, `CallId`, `ToolName`, `MessageId`, aber **keine gebrandeten Seq/Cursor/Version-Tokens**. Lernpunkt: **Branded Types für Seq, Offset, Version-Tokens** einführen, um Verwechslungen (z. B. `updated()`-Instant vs. Seq) zur Compile-Zeit zu verhindern. Besonders `FsVersion` und `FsTargetKey` als Opaque-Tokens.

### 2.2 Opaque Targets/Versionen im Filesystem
`FsTargetKey` (Opake Key, Backend bestimmt Repräsentation), `FsVersion` (Opake Freshness-Token). Consumers **dürfen nicht parsen**. `FsObservation = present(version) | absent`. `FsWriteIntent = createIfAbsent | replaceIfVersion(version)`.  
**QOC-Bezug:** QOC's Filesystem-Tools haben keine Versions-Stale-Guards. Lernpunkt: **Version-basierte Stale-Guards** (`replaceIfVersion`) + `createIfAbsent` (atomares Create) einführen. Verhindert Lost-Update bei concurrent Edits.

### 2.3 FsWriteOutcome mit before/after
`FsWriteOutcome` trägt `before: string | null` (LF-normalisiert, vor dem Write) und `after: string`. `FsEditOutcome` trägt `before`/`after`. `FsDeleteOutcome` trägt `before`.  
**QOC-Bezug:** QOC's Edit-Tool gibt nur Erfolg/Fehler. Lernpunkt: **before/after im Ergebnis** zurückgeben, damit UI/Diff/Revert ohne Re-Read auskommen. QOC hat Revert-Staging — das würde davon profitieren.

### 2.4 Typed FsError-Codes
`FS_ERROR_CODES` = 15 stabile Codes (`FS_NOT_FOUND`, `FS_STALE_VERSION`, `FS_AMBIGUOUS_EDIT`, `FS_PATCH_MISMATCH`, …). `FsError extends HarnessError`.  
**QOC-Bezug:** QOC hat `FailureKind` für Provider, aber **keine typisierten FS-Fehlercodes**. Lernpunkt: **FS-Error-Taxonomie** (wie `FailureKind` für Provider) einführen.

### 2.5 Empty-is-Absent (Credentials)
Leerer gespeicherter Wert = absent überall. `CredentialInfo { configured, source?, writable }`.  
**QOC-Bezug:** QOC's Settings/Provider-Config. Lernpunkt: **Empty-is-Absent-Regel** konsistent anwenden (Provider-URL, API-Key).

---

## 3. Architektur-Prinzipien

### 3.1 Capability Seams (Service Definition / Provider / Consumer)
Jede Fähigkeit ist Dreiklang: Service Definition (Interface), Service Provider (Impl), Consumer (z. B. Tool). Ein Provider-Swap ändert das ganze Produkt.  
**QOC-Bezug:** QOC hat Ports (`ProviderAdapter`, `HttpTransport`, `SessionStore`). Lernpunkt: **jede** externe Fähigkeit (Spill, Compaction, Sandbox, Approval, Credentials, Storage) als expliziten Seam mit drei Rollen definieren.

### 3.2 "Model-visible means logged"
Jede Modell-Anfrage muss aus dem Log rekonstruierbar sein. Neue modell-sichtbare Inputs brauchen Session-Events.  
**QOC-Bezug:** QOC hat Event-Log. Lernpunkt: **Streng durchsetzen**, dass kein modell-sichtbarer State nur im Speicher liegt. Runtime-Context (Working Directory, Approval-Policy, Plan-Mode) muss als Event in den Log.

### 3.3 Pure Message Projections
Plugins ändern bestehende Message-Inhalte über **pure Projections** (keine Mutation), Detached Readers liefern dieselben Definitionen.  
**QOC-Bezug:** QOC's `ConversationFolding`. Lernpunkt: **Projections als erstklassiges Konzept**.

### 3.4 Capability-Events (fs/*, tools/*, telemetry/*)
Policy/Hooks greifen über Events ein, ohne den Loop zu importieren.  
**QOC-Bezug:** QOC hat keine Capability-Events. Lernpunkt: `fs/*`, `tools/*`, `telemetry/*` als Event-Domänen einführen.

### 3.5 Working Directories als eigene Schicht
Aktives Verzeichnis ist Event (`working-directory/change`), immutable Header identifiziert Original-Projekt. Sandbox-Write-Roots bleiben am Original verankert.  
**QOC-Bezug:** Lernpunkt: **Sandbox-Roots dürfen nicht mit cwd wandern** — wichtige Sicherheitsinvariante.

---

## 4. Session-Log & Replay

### 4.1 Adjacent Migrations (immutable Generations)
Session-Format-Versionen als `vN`-Dateien, nie umbenannt/überschrieben/gelöscht. `stat`/`list` wählen höchste kanonische Generation; `open` dekodiert Migrationskette. Migration fügt nur fehlendes `turn/end` ein, wenn schon späteres `turn/start` existiert. `SESSION_FORMAT_VERSION = 4`.  
**QOC-Bezug:** QOC hat Flyway für DB-Schema, aber **keine Format-Versionierung für Event-Log**. Lernpunkt: Wenn QOC persistiertes Event-Format wechselt, immutable Generations + adjacent migrations.

### 4.2 Surface vs. Shadowed vs. Log-only
Jedes Event ist `current` (modell-sichtbar), `shadowed` (durch Compaction ersetzt) oder `log-only` (Audit). `foldSurface()` berechnet modell-sichtbare Sicht. `SessionEventSurface = 'current' | 'shadowed' | 'log-only'`.  
**QOC-Bezug:** QOC hat keine Surface-Klassifikation. Lernpunkt: **Surface-Klassifikation** als First-Class-Konzept.

### 4.3 Session Projections (fold committed events)
Domain-Plugins registrieren "Units", die committed Events falten; Carriers lesen fertige Werte. Registry subscribed einmal auf `session/event`. `ProjectionHandle` behält Reader auch nach Unregistration (`stateOf`, `readerFor`, `dispose`). Summary-excluded Keys (`SessionProjectionSummaryExcluded`).  
**QOC-Bezug:** QOC's `ConversationFolding`. Lernpunkt: **Projections als Service** mit detached Readers.

### 4.4 Session Query (cross-corpus)
`SessionRecord { header, live, persisted, formatStatus? }`. `SessionLogSnapshot` (detached, replay-validated). `SessionSurfaceSnapshot` (exact-read). Filter + Relationship-Tracing.  
**QOC-Bezug:** QOC hat `SessionStore` + `Journal`. Lernpunkt: **Unified Query-Schicht** über live + persisted.

### 4.5 Session Title (latest-wins, pinned)
`SessionTitleSource = fallback | provider (mit ModelIdentity) | user`. User-Rename pinnt (stopt Auto). `TitleInputState` als bounded Aggregate: `{ first, count, lastSeq }`.  
**QOC-Bezug:** QOC hat `TitleGenerator`. Lernpunkt: **Pinning-Semantik** + **Model-Identity-Tracking** + **Bounded Aggregate** für Title-Input.

### 4.6 Branded SessionSeq/SessionLogOffset
`SessionSeq` = non-negative safe integer branded. `SessionSeqCursor = SessionSeq | -1`. `OptionalSessionSeq = SessionSeq | null`.  
**QOC-Bezug:** QOC nutzt `Seq` (long). Lernpunkt: **Typsicherheit** für Seq vs. Offset vs. Cursor.

---

## 5. Turn/Step-Lifecycle

### 5.1 Claiming + Pre-Step Waterfall
Turn öffnet, claimt next-step input + eine queued Message. `agent/pre-step` ist Waterfall (accept/rewrite/reject). Rejected/empty first claim schließt Turn ohne Step. `startsRequestSeries` Declaration.  
**QOC-Bezug:** QOC hat `Step` + `StepResult`. Lernpunkt: **Pre-Step-Hook als Waterfall** für Kontext-Injection, Steering, Policy-Checks.

### 5.2 Prepare-Call mit Cancellation-Semantik
`prepareCall()` bindet Adapter; Cancellation während async Phase committet weder System noch Users.  
**QOC-Bezug:** QOC's `Step.run`. Lernpunkt: **Cancellation-Commitment-Garantie** dokumentieren und testen.

### 5.3 Freeze Request from Log
Request wird aus dem Log abgeleitet und eingefroren (immutable). Retry nutzt Freeze-Evidence. Loop-built requests sind **deep-frozen**.  
**QOC-Bezug:** QOC hat `ModelRequest`. Lernpunkt: **Request-Freeze** als explizites Konzept.

### 5.4 request/header & request/context Events
`request/header` (Modell-Selection-Snapshot) und `request/context` (Runtime-Facts) als separate durable Events.  
**QOC-Bezug:** QOC hat `ModelRequest` + `GenerationOptions`. Lernpunkt: **Model-Selection-Snapshot pro Request** als Event.

### 5.5 Retry ohne Re-Assembly
Retry wiederholt nicht Assembly/Pre-Step/User-Admission, sondern nur prepare+reconcile.  
**QOC-Bezug:** QOC hat keine Retry-Logik im Provider (nur FailureKind). Lernpunkt: **Retry-Semantik** dokumentieren.

### 5.6 turn-stopping (Serial, Terminal Checkpoint)
`agent/turn-stopping` ist serial (kein `next()`), Terminal-Checkpoint vor `turn/end`.  
**QOC-Bezug:** QOC hat `TurnOutcome`. Lernpunkt: **Terminal-Checkpoint-Hook**.

### 5.7 Failed Steps record missing tool results
Fehlgeschlagene Steps protokollieren fehlende Tool-Results.  
**QOC-Bezug:** QOC's `StepResult.Failed`. Lernpunkt: **Invarianten-Test**.

### 5.8 Claimed Batch stays removed on reject
Bei rejected/empty first claim bleibt der claimed Batch entfernt, Turn gibt keinen Step aus.  
**QOC-Bezug:** QOC. Lernpunkt: **Claimed-Input-Semantik** bei Rejection.

---

## 6. Tool-Execution-Pipeline

### 6.1 Pre/Execute/Post Waterfalls
`tools/pre-execute` (Hooks, Permission, Sandbox), `tools/execute` (Timeout, Retry, Metrics around dispatch), `tools/post-execute` (accept/block/replace/add context). Monotonic Guards (deny/abstain) laufen zwischen pre und execute. Guards werfen → normalized.  
**QOC-Bezug:** QOC hat Permission-Gate + ToolRuntime. Lernpunkt: **Drei-Phasen-Pipeline** als explizites Design.

### 6.2 Approval vor Guards
`ctx.approval` löst Asks **vor** monotonic Guards. Owner-Policy, die nicht reorderbar sein darf, bleibt Guard.  
**QOC-Bezug:** QOC hat HITL/ASK. Lernpunkt: **Reihenfolge** dokumentieren.

### 6.3 fs/write-intent & fs/edit-intent
Tool-FS-Mutationen gehen über `fs/*` Events (read-before-edit Checks).  
**QOC-Bezug:** QOC hat Filesystem-Tools. Lernpunkt: **Intent-Events** für FS-Mutationen.

### 6.4 projectContent / finalizeContent
`ToolDefinition.projectContent` installiert prepared content vor post-execute; `finalizeContent` ist letzte content-only Invariante. Pipeline snapshottet candidate result und normalisiert Snapshot-Failure.  
**QOC-Bezug:** QOC's Tool-Result-Handling. Lernpunkt: **Zwei-Stufen-Content-Transformation**.

### 6.5 additionalContexts (FIFO)
Nach recorded tool results werden additionalContexts als `user/message` injiziert.  
**QOC-Bezug:** QOC. Lernpunkt: **Post-Tool-Context-Injection**.

### 6.6 PTC Mode (Programmatic Tool Calling)
`run_code` transport + sub-calls durch dieselbe Pipeline; sub-calls tragen parent token, loggen `tool/ptc-dispatch`, Denials als binding rejections, keine additionalContexts.  
**QOC-Bezug:** QOC hat keine PTC. Lernpunkt: **PTC als optionale Capability**.

### 6.7 presentCall / presentResult (UI Cards)
UI pending/completed cards als Hooks.  
**QOC-Bezug:** QOC hat UI. Lernpunkt: **Tool-UI-Hooks** entkoppelt.

### 6.8 Timeout-Policy & Repeat-Tool-Reminder (Guards)
`guard/` family: repeat-tool-reminder + timeout-policy. Beide shippen enabled in base bundle.  
**QOC-Bezug:** QOC hat keine Loop-Guards. Lernpunkt: **Loop-Hygiene-Guards** (Repeat-Detection, Timeout).

---

## 7. Compaction (code-level)

### 7.1 Lock-Brackets (start/end)
`compaction/start` (Lock) → Summarization → `compaction/summary` → `user/message` (replace) → `compaction/end` (Release). Lock zuerst, Release zuletzt → Crash mid-op = detectable orphaned lock.  
**QOC-Bezug:** QOC hat `CompactionService`. Lernpunkt: **Lock-Event-Pattern**.

### 7.2 Surface Replacement via surfaceOp
Summary ersetzt surface-Bereich über `surfaceOp: { op: 'replace', startSeq, endSeq }` auf `user/message`. Shadowed Seqs werden trackt. `shadowedRange` ist Position-Span, nicht numerisches Interval (nach prior replace kann start > end).  
**QOC-Bezug:** QOC's `ConversationFolding`. Lernpunkt: **Surface-Op als explizites Event-Feld**.

### 7.3 Tool-Result Pruning vor Summary
Optional `ctx.toolResultPruner` ersetzt große Tool-Results durch head/tail + Spill-Locator. Läuft vor Range-Selection, remeasured via tokenMeter. `PrunedEntry { originalSeq, replacementSeq, callId, charsBefore, charsAfter }`.  
**QOC-Bezug:** QOC hat `ToolResultCache`. Lernpunkt: **Tool-Result-Pruning als separate Capability**.

### 7.4 Image Offload
`image/offload` Event mit `seq` + `imageIndexes` (depth-first). Persist-before-event.  
**QOC-Bezug:** QOC hat `ModelContent.Media`. Lernpunkt: **Image-Offload-Event**.

### 7.5 Trigger-Klassen
`CompactionTrigger = 'pressure' | 'context-overflow'`; overflow aggressiver.  
**QOC-Bezug:** QOC's Compaction. Lernpunkt: **Zwei Trigger-Klassen**.

### 7.6 Manual Error Codes
`ManualCompactionErrorCode = 'busy' | 'cancelled' | 'changed' | 'summary' | 'commit' | 'persistence'`.  
**QOC-Bezug:** QOC. Lernpunkt: **Granulare Fehlercodes**.

### 7.7 Tool-Pairing Boundaries
`toolPairingBalancedBefore(session, seq)` und `toolPairingBalancedAfter(session, seq)` — validieren surface membership, reject missing seqs and orphan results.  
**QOC-Bezug:** QOC. Lernpunkt: **Tool-Call/Result-Pairing als Invariante**.

### 7.8 CompactionId (Stable Identity)
`CompactionId` als stable identity für den gesamten durable Lifecycle. `compactCheckpointSource(compactionId, sourceCommandId?)` für Replacement-Checkpoint.  
**QOC-Bezug:** QOC. Lernpunkt: **Compaction-Identity** für Korrelation.

---

## 8. Spill Storage (code-level)

### 8.1 Opaque Locators
`SpillLocator` branded/opaque; lokales Backend nutzt Pfad, remote nutzt URI/Key. Consumers rendern mit `retrievalHint`, parsen nicht. `SpillRef { locator, bytes, retrievalHint }`.  
**QOC-Bezug:** QOC hat `ToolResultCache` + ArtifactRefs. Lernpunkt: **Opaque Locators**.

### 8.2 Best-Effort Policy
Spill-Policy ersetzt over-`maxInlineTokens` Result durch head/tail + Spill-Adresse; bei Save-Failure bleibt Original inline.  
**QOC-Bezug:** QOC. Lernpunkt: **Best-Effort-Spill**.

### 8.3 Symlink-Safety
`open(path, 'wx', 0o600)` — exclusive write, 0600. Session-subdir via `sha256(sessionId)`.  
**QOC-Bezug:** QOC's Filesystem-Tools. Lernpunkt: **Symlink-Safe Writes**.

### 8.4 Retention
Spill folgt Session-Retention.  
**QOC-Bezug:** QOC's ToolOutputStore sweep. Lernpunkt: **Spill-Retention an Session koppeln**.

### 8.5 SaveTextSpill Request
`{ owner: { sessionId }, source: tool|session-reference, suggestedName, content }`. `source` beschreibt Produzent, nie Access Control.  
**QOC-Bezug:** QOC. Lernpunkt: **Spill-Request mit Owner/Source-Trennung**.

---

## 9. Subagents (code-level)

### 9.1 Capability Flags
`SubagentCapabilities { agentOptions, agentPreset, outputSchema, depthLimit }`. Manager prüft vor Prepare.  
**QOC-Bezug:** QOC hat `agent_create`. Lernpunkt: **Capability-Flags**.

### 9.2 Activation vs. PrepareContinuable
`startActivation` unified; `prepareContinuable` für lokale Child-Agents, `start` für externe.  
**QOC-Bezug:** QOC's A2A/MCP. Lernpunkt: **Unified Activation API**.

### 9.3 Delivery Modes
`delivery: 'parent' | 'caller'`.  
**QOC-Bezug:** QOC. Lernpunkt: **Delivery-Mode**.

### 9.4 Structured Output Schema
`outputSchema` als Object-JSON-Schema; Child liefert `structured` Value.  
**QOC-Bezug:** QOC hat `SubmitResult`. Lernpunkt: **Structured Output für Subagents**.

### 9.5 Depth Limit
`maxDepth` Cap; computed depth muss ≤ cap.  
**QOC-Bezug:** QOC. Lernpunkt: **Rekursions-Schutz**.

### 9.6 cwd Inheritance
Child erbt parent's effective directory at admission; relative paths resolve against parent.  
**QOC-Bezug:** QOC's Ephemeral Sessions. Lernpunkt: **cwd-Vererbung**.

### 9.7 Catalog Rows (one-shot/external/continuable)
`SubagentCatalogRow` mit `mode: 'one-shot' | 'external' | 'continuable'`. Historical children mit unavailable descriptors bleiben `mode: 'unknown'`.  
**QOC-Bezug:** QOC's A2A Agent Cards. Lernpunkt: **Mode-Klassifikation** für Subagents.

### 9.8 SubagentPromptRequest (queue/steer)
`delivery: 'queue' | 'steer'` für Messages an continuable children.  
**QOC-Bezug:** QOC. Lernpunkt: **Steering vs. Queuing**.

---

## 10. Approval (code-level)

### 10.1 Per-Session Policy (ask/never)
`ApprovalPolicy = 'ask' | 'never'`; `never` gibt deterministisch `rejected`. Effective value = letztes `approval/policy` Event, Fallback Service-Config.  
**QOC-Bezug:** QOC hat ASK + Permission-Gate. Lernpunkt: **Per-Session Approval-Policy als Event**.

### 10.2 Fail-Closed
`ApprovalOutcome = 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'`. Missing/throwing answerer → `unavailable`.  
**QOC-Bezug:** QOC. Lernpunkt: **Fail-Closed-Semantik**.

### 10.3 Audit Events (log-only)
`approval/asked` + `approval/decided` als log-only Pair, korreliert über `ApprovalRequestId`.  
**QOC-Bezug:** QOC. Lernpunkt: **Audit-Pair mit Request-ID**.

### 10.4 Keine Arguments in Request
`ApprovalRequest` enthält `callId`, nicht Arguments. `displayReason` für Locale-Strings.  
**QOC-Bezug:** QOC's `ToolOptions.approvalMessage`. Lernpunkt: **Arguments nicht duplizieren**.

### 10.5 Scoped Dispatch
`approval/request` ist Waterfall mit Scope-Filterung (agent-scoped listeners).  
**QOC-Bezug:** QOC. Lernpunkt: **Scoped Answerer-Dispatch**.

---

## 11. Sandbox (code-level)

### 11.1 Modes
`SandboxMode = 'read-only' | 'workspace-write' | 'danger-full-access'`. Nur erste zwei an Provider.  
**QOC-Bezug:** QOC hat keine Sandbox. Lernpunkt: **Sandbox-Modi**.

### 11.2 Enforcement (full/partial)
`SandboxEnforcement = 'full' | 'partial'`.  
**QOC-Bezug:** QOC. Lernpunkt: **Enforcement als Reported Fact**.

### 11.3 Per-Call Policy
Policy pro Call aufgelöst, nicht provider-global. `SandboxPolicyRequest { session?, mode? }`. `SandboxExecutionPolicy { mode, workspaceRoot, additionalWritableRoots?, sessionId? }`.  
**QOC-Bezug:** QOC. Lernpunkt: **Per-Call Policy Resolution**.

### 11.4 Runner Failure Rules
`RunnerFailureRule { allowedExitCodes?, fatalSignatures[], informationalLines[] }`. Case-insensitive. Exit-Status allein beweist nichts.  
**QOC-Bezug:** QOC. Lernpunkt: **Strukturierte Runner-Failure-Klassifikation**.

### 11.5 Escalation
Nach denied Call kann Modell einen strikt weiteren Mode für human approval anfordern.  
**QOC-Bezug:** QOC. Lernpunkt: **Escalation-Flow**.

---

## 12. Skills (code-level)

### 12.1 Ranked Discovery
Roots in Rank-Order (project-dsh 100, project-agents 200, custom 300, user-dsh 400, user-agents 500, bundled 600). Duplicate names: nearest layer wins; innerhalb Layer: rank, provider, local order.  
**QOC-Bezug:** QOC hat BM25-Skill-Loading. Lernpunkt: **Rank-basierte Discovery** + Layer-Precedence.

### 12.2 Skill Identity
Kebab-case `^[a-z0-9]+(?:-[a-z0-9]+)*$`. Directory bundles (`<name>/SKILL.md`) + flat files (`<name>.md`). Keine rekursive `**/SKILL.md`.  
**QOC-Bezug:** QOC. Lernpunkt: **Skill-Naming-Convention**.

### 12.3 Invocation Policy
`SkillInvocationPolicy { modelInvocable, userInvocable }` als positive Booleans.  
**QOC-Bezug:** QOC hat per-Agent allow/deny. Lernpunkt: **Explizite Invocation-Policy**.

### 12.4 Incomplete Discovery
Rejected `list()` wird geloggt und aus Observation ausgeschlossen; incomplete observation liefert usable candidates ohne Cache. `SkillProviderObservation { candidates, complete }`.  
**QOC-Bezug:** QOC. Lernpunkt: **Graceful Degradation**.

### 12.5 Chokidar-Watcher
Bestehende Roots werden gewatched. Missing Root wird segment-weise verfolgt. Model-facing `write`/`edit` invalidieren synchron.  
**QOC-Bezug:** QOC. Lernpunkt: **File-Watcher für Skill-Roots**.

### 12.6 Provider Registry (host+per-scope layered)
Registration files into layer of calling context's scope. Discovery caches keyed by scope chain.  
**QOC-Bezug:** QOC. Lernpunkt: **Scoped Skill-Registry**.

---

## 13. Attachments (code-level)

### 13.1 Content-Addressed
`AttachmentId` = `sha256:<digest>` (branded). Consumers parsen nicht.  
**QOC-Bezug:** QOC hat `ModelContent.Media`. Lernpunkt: **Content-Addressed IDs**.

### 13.2 Persist-Before-Event
Object wird **vor** dem Event durable. Session-Events enthalten nur Referenz + Metadata.  
**QOC-Bezug:** QOC. Lernpunkt: **Persist-Before-Event**.

### 13.3 ImageRequestTarget & RequestImageAttachment
Deterministischer Request-Image-Target pro Modell-Route. `RequestImageAttachment` als cached request version (variantId, attachment, data, mediaType, bytes, width, height, depth, space, hasAlpha).  
**QOC-Bezug:** QOC. Lernpunkt: **Image-Request-Caching** (Variant-basiert).

### 13.4 Admission Parts
`AttachmentAdmissionPart` / `AdmittedPromptContentPart` — Host-admitted content mit durable refs.  
**QOC-Bezug:** QOC. Lernpunkt: **Admission-Pipeline** für Binaries.

---

## 14. Deliverables (code-level)

### 14.1 `present` Tool
Modell deklariert Dateien als Deliverable (log-only Event). `PresentedFile { path, description? }`.  
**QOC-Bezug:** QOC hat keine Deliverables. Lernpunkt: **Deliverables als First-Class**.

### 14.2 Workspace Changes
Git-Snapshot am Turn-Start/-Ende + whole-file Captures um File-Tool-Edits. `WorkspaceChangedFile { path, display, added, deleted, binary? }`.  
**QOC-Bezug:** QOC hat `RevertStaged`/`RevertCommitted`. Lernpunkt: **Turn-Diff als Deliverable**.

### 14.3 Move-Semantik
Move contributes deleted source + added destination. Stopped commit contributes only net changes.  
**QOC-Bezug:** QOC. Lernpunkt: **Move-Accounting**.

---

## 15. Background Jobs (code-level)

### 15.1 Job Lifecycle Records
`job/registered` + `job/settled` Events im Owner-Session. `JobId = <kind>-<short-uuid>` (16 hex). `JobStatus = running | stopping | completed | killed | failed`.  
**QOC-Bezug:** QOC hat `ExecutionStarted/Succeeded/Failed`. Lernpunkt: **Job-Events im Session-Log**.

### 15.2 producerReported
`producerReported` = Producer fulfilled `done` mit terminal outcome. False für forced teardown/rejection.  
**QOC-Bezug:** QOC. Lernpunkt: **Teardown vs. Result Trennung**.

### 15.3 Missing Settlement bleibt incomplete
Ohne `job/settled` im Log = incomplete, auch wenn Registry termination meldet.  
**QOC-Bezug:** QOC. Lernpunkt: **Log als Wahrheit**.

### 15.4 Ownership (scoped to agent session)
Andere Agents können nicht inspect/stop.  
**QOC-Bezug:** QOC. Lernpunkt: **Job-Ownership**.

---

## 16. Schedule (code-level)

### 16.1 7 Timing Kinds
`after`, `at`, `every`, `daily`, `weekly`, `cron`, `rrule`.  
**QOC-Bezug:** QOC hat keine Scheduling. Lernpunkt: **Scheduling als Capability**.

### 16.2 Storage Versioning
`compatibleVersions` für alte Stamps. Normal edits persist current rows. Reads lassen Storage unverändert.  
**QOC-Bezug:** QOC. Lernpunkt: **Storage-Versioning-Pattern**.

### 16.3 Latest-Only Catch-Up
Verpasste Occurrences: nur letzte einmal liefern, dann erste zukünftige.  
**QOC-Bezug:** QOC. Lernpunkt: **Catch-Up-Semantik**.

### 16.4 Canonical UTC Instants
`scheduledAt` = RFC 3339 UTC, ms-Precision, Jahr 0001–9999. Decoding recalculiert nicht.  
**QOC-Bezug:** QOC. Lernpunkt: **Canonical Time Storage**.

---

## 17. Goals (code-level)

### 17.1 Revision-basiert (CAS)
`GoalRef = { id, revision }`; jede durable Mutation inkrementiert Revision.  
**QOC-Bezug:** QOC. Lernpunkt: **Compare-and-Set** für Shared State.

### 17.2 Phases
`GoalPhase = active | paused | blocked | complete`. `blocked` mit `GoalBlockReason { code, message }`.  
**QOC-Bezug:** QOC. Lernpunkt: **Goal-Lifecycle**.

### 17.3 Activation (process-local)
`GoalActivation = armed | disarmed`. Nie persisted.  
**QOC-Bezug:** QOC. Lernpunkt: **Process-Local vs. Durable State Trennung**.

### 17.4 Round Cap
`maxGoalRounds` (default 256) begrenzt automatische Continuation.  
**QOC-Bezug:** QOC. Lernpunkt: **Round-Cap** für Agent-Loops.

---

## 18. Plan Mode (code-level)

### 18.1 Soft Guidance
Plan-Mode ist soft guidance (nicht Sandbox/Approval). Eigenes Event `plan/mode`, log-only, whole-value-replace.  
**QOC-Bezug:** QOC. Lernpunkt: **Plan-Mode als Capability**.

### 18.2 Pending Selections
User-Selection bleibt pending bis zum nächsten accepted in-turn pre-step. `PlanProjection { active, pending }`. `PlanUnitState { active, wanted, running, activeAtLastHeader, entered }`.  
**QOC-Bezug:** QOC. Lernpunkt: **Pending-Selection-Pattern**.

### 18.3 exit_plan_mode Tool
Progressiv disclosed; requires complete markdown plan mit `#` heading; präsentiert zur Review (user-questions seam).  
**QOC-Bezug:** QOC. Lernpunkt: **Plan-Review-Flow**.

### 18.4 `/plan` Command
`/plan [off|message]`: bare → select plan mode; mit message → select + steer; `off` → inactive + cancel pending.  
**QOC-Bezug:** QOC. Lernpunkt: **Command-basierte Mode-Toggle**.

---

## 19. Todo (code-level)

### 19.1 Whole-List Replacement
`todo/write` ersetzt ganze Liste (last-wins), kein Id/Priority/activeForm. `TodoItem { content, status: pending|in_progress|completed }`.  
**QOC-Bezug:** QOC hat `TodoTool`. Lernpunkt: **Minimal-Design**.

### 19.2 Excluded from Summary
`todos: SessionProjectionSummaryExcluded<TodoItem[]> | null` — nicht in Session-Summary.  
**QOC-Bezug:** QOC. Lernpunkt: **Summary-Exclusion** für große Projections.

---

## 20. Working Directory (code-level)

### 20.1 Immutable Header + Change Events
Original Project bleibt Anker. `working-directory/change { cwd }`.  
**QOC-Bezug:** QOC. Lernpunkt: **Sandbox-Roots nicht mit cwd wandern**.

### 20.2 Serialize Queue per Session
`serialize(agent, signal, action)` — per-Session Promise-Chain für ordered Mutations.  
**QOC-Bezug:** QOC. Lernpunkt: **Per-Session Serialization** für Directory-Mutations.

### 20.3 ensure() restores original
Wenn cwd verschwunden, restore original project directory.  
**QOC-Bezug:** QOC. Lernpunkt: **Recovery-Pattern**.

### 20.4 Notice via inject()
Commit queued notice via `agent.inject()` (user/message). Failure wird gewarnt, aber committed state bleibt.  
**QOC-Bezug:** QOC. Lernpunkt: **Best-Effort Notice** nach Commit.

### 20.5 Required User-Context
Working directory als `required: true` user-context snapshot, auch wenn optional runtime context deaktiviert.  
**QOC-Bezug:** QOC. Lernpunkt: **Required Context** (nicht optional).

---

## 21. Workspace (code-level)

### 21.1 Canonical Paths
`realpathNormalize` (resolve symlinks, `..`, trailing slashes) als einzige Uniqueness-Canon.  
**QOC-Bezug:** QOC. Lernpunkt: **Canonical Path Normalization**.

### 21.2 Session Membership Validation
Workspace validiert Session-cwd gegen Workspace-Path. Missing header preserve candidate.  
**QOC-Bezug:** QOC. Lernpunkt: **Membership-Validation**.

### 21.3 Status (ok/missing-dir)
Live directory check, uncached. Missing directory mutiert nicht den Record.  
**QOC-Bezug:** QOC. Lernpunkt: **Non-Mutating Status Check**.

### 21.4 Activity Families
`SessionActivityKindMap` merge-extensible (turn, job, subagent, schedule).  
**QOC-Bezug:** QOC. Lernpunkt: **Activity-Family-Registry**.

---

## 22. Credentials (code-level)

### 22.1 References, not Values
Settings enthalten nur Referenzen (Env-Var-Namen), Provider besitzt Werte. `CredentialRef` branded.  
**QOC-Bezug:** QOC hat Provider-Auth. Lernpunkt: **Credential-References**.

### 22.2 Per-Operation Resolve
Resolve pro Operation, nie gecacht → Hot-Update.  
**QOC-Bezug:** QOC. Lernpunkt: **Per-Operation Resolve**.

### 22.3 Empty = Absent
Leerer Wert = absent.  
**QOC-Bezug:** QOC. Lernpunkt: **Empty-is-Absent**.

### 22.4 CredentialInfo
`{ configured, source?, writable }` — sichere View für UI, nie Wert.  
**QOC-Bezug:** QOC. Lernpunkt: **Safe Credential View**.

### 22.5 CredentialKey (scope/id)
`<scope>/<id>` für stored records. Scope = owner plugin.  
**QOC-Bezug:** QOC. Lernpunkt: **Scoped Credential Keys**.

### 22.6 Resolution Events
`credentials/reference-updated` + `credentials/record-updated` als emit-Events.  
**QOC-Bezug:** QOC. Lernpunkt: **Credential-Change-Events**.

---

## 23. Settings (code-level)

### 23.1 Form Projection
Config-Felder zu Forms. `SettingsNamespaceView { autoGenerate, ns, schema, value, base?, user?, applies, secrets, revision }`.  
**QOC-Bezug:** QOC hat Settings. Lernpunkt: **Form-Projection**.

### 23.2 Optimistic Concurrency
`revision` monotonic; write mit `expectedRevision`; stale refused.  
**QOC-Bezug:** QOC. Lernpunkt: **Optimistic Concurrency**.

### 23.3 Path-Ops
`SettingsPathOpView = { op: 'set', path, value } | { op: 'unset', path }`. Empty path = root.  
**QOC-Bezug:** QOC. Lernpunkt: **Path-addressed Edits**.

### 23.4 Secret Redaction
`SettingsSecretView { path, set }` — Value nie im Wire.  
**QOC-Bezug:** QOC. Lernpunkt: **Secret-Redaction im Wire**.

---

## 24. Storage (code-level)

### 24.1 Hub/Backend/Domain Form
`ctx.storage` ist Meeting-Point. Backends (json/sqlite) own Media; Domain Form owns Semantics. Hub macht kein IO. `register(name, backend)` returns disposer. `mount(form, facility)` effect.  
**QOC-Bezug:** QOC hat `SessionStore`/`Journal`/`ToolOutputStore`. Lernpunkt: **Storage-Hub-Pattern**.

### 24.2 Backend Contract
`StorageBackend { kv?, ... }`. Facets optional; fehlend = resolution fails loud. `close()` idempotent.  
**QOC-Bezug:** QOC. Lernpunkt: **Facet-basierte Backends**.

### 24.3 Domain Versioning
Shared domain-version policy für JSON + SQLite.  
**QOC-Bezug:** QOC. Lernpunkt: **Versionierte Storage-Domains**.

### 24.4 storageBackendServiceKey
Lifecycle-only service key für Backend-Registration.  
**QOC-Bezug:** QOC. Lernpunkt: **Backend-Lifecycle-Sync**.

---

## 25. MCP (code-level)

### 25.1 Scope-basiert
MCP-Server opt-in pro Scope. Kein Server → keine Tools/Prompt-Text.  
**QOC-Bezug:** QOC hat MCP Client/Server. Lernpunkt: **Scope-basierte Server-Sichtbarkeit**.

### 25.2 Server Instructions in System Prompt
Server-Instructions joinen logged System Prompt (mit Size-Limit `maxInstructionBytes`).  
**QOC-Bezug:** QOC. Lernpunkt: **MCP-Instructions als Prompt-Section**.

### 25.3 Resources als Tools
Resources discover/read als normale Tools mit Permission + recorded results. Binary payloads als descriptions.  
**QOC-Bezug:** QOC. Lernpunkt: **MCP-Resources als First-Class-Tools**.

### 25.4 Tool-Naming
`mcp__<server>__<tool>`.  
**QOC-Bezug:** QOC. Lernpunkt: **Namespacing-Convention**.

### 25.5 Protocol Negotiation
SDK's supported revisions; kein Product-Setting für Revision.  
**QOC-Bezug:** QOC. Lernpunkt: **SDK-managed Protocol Negotiation**.

---

## 26. System Prompt (code-level)

### 26.1 Sections mit Order
Prompt-Sections mit Order (z. B. `plan:policy` bei 50). `system-prompt/assemble` Waterfall. `context()` mit `name`, `order`, `required`, `interpolate`, `text`.  
**QOC-Bezug:** QOC hat `ContextBuilder`. Lernpunkt: **Ordered Sections + Waterfall-Assembly**.

### 26.2 Capability-independent Tool Changes
Tool-Changes sind capability-independent; Session Tool History liefert Provider-Declarations.  
**QOC-Bezug:** QOC. Lernpunkt: **Tool-History als separate Concern**.

### 26.3 Required User-Context
`required: true` user-context snapshot, auch wenn optional runtime context deaktiviert.  
**QOC-Bezug:** QOC. Lernpunkt: **Required Context**.

---

## 27. LLM (code-level)

### 27.1 Streams enden immer mit Terminal Result
Jeder Stream endet mit success/failure/cancellation.  
**QOC-Bezug:** QOC hat `ModelEvent.Finished`/`ProviderFailed`. Lernpunkt: **Terminal-Result-Garantie**.

### 27.2 Loop-built Requests deep-frozen
Extensions/Adapter können Requests nicht rewrites.  
**QOC-Bezug:** QOC. Lernpunkt: **Immutability von Requests**.

### 27.3 Provider-specific Translation in Adapter
Provider-spezifische Translation bleibt im Adapter.  
**QOC-Bezug:** QOC. Lernpunkt: **Adapter-Isolation** (QOC hat das schon).

### 27.4 Model Discovery
Model-Metadata + Call-Defaults resolution.  
**QOC-Bezug:** QOC hat `ModelInfo`/`StaticCatalogSource`. Lernpunkt: **Dynamic Model Discovery**.

---

## 28. Guard (code-level)

### 28.1 Repeat-Tool-Reminder
Erkennt wiederholte identische Tool-Calls, erinnert Modell.  
**QOC-Bezug:** QOC hat keine Loop-Guards. Lernpunkt: **Repeat-Detection**.

### 28.2 Timeout-Policy
Time-Limit für Tool-Calls mit deklariertem Timeout.  
**QOC-Bezug:** QOC. Lernpunkt: **Tool-Timeout-Policy**.

---

## 29. Identity & Telemetry (code-level)

### 29.1 Anonymous User ID
Ein Anonymous-Id pro Harness-Home für Telemetry/Feedback/Provider-Requests.  
**QOC-Bezug:** QOC. Lernpunkt: **Installation-Identity** für Telemetry.

### 29.2 OTEL Transport Plugins
Shared telemetry transport, business consumers wählen Events/Redaction.  
**QOC-Bezug:** QOC hat `LlmTrace`. Lernpunkt: **OTEL-Integration**.

---

## 30. Extensions (code-level)

### 30.1 Read-only API Discovery
Runtime API inspection für Agents.  
**QOC-Bezug:** QOC. Lernpunkt: **API-Discovery** für Agents.

### 30.2 Process-local Runners
Programmatic + browser consumers.  
**QOC-Bezug:** QOC. Lernpunkt: **Process-local Execution**.

---

## 31. Preset (code-level)

### 31.1 Agent Capabilities via Config
Agent-Capabilities durch ordinary Cordis Config. Revision-Management.  
**QOC-Bezug:** QOC hat `AgentProfile`/`AgentSelection`. Lernpunkt: **Config-basierte Agent-Composition**.

---

## 32. PTC Runtime (code-level)

### 32.1 Fresh Process per Async Function
Node evaluates async function bodies in fresh processes. REPL retains one process per state owner. Script retention optional.  
**QOC-Bezug:** QOC hat keine PTC. Lernpunkt: **PTC-Execution-Model**.

### 32.2 Failures as Results
Failures return as results, nicht exceptions.  
**QOC-Bezug:** QOC. Lernpunkt: **Failure-as-Result**.

---

## 33. Document (code-level)

### 33.1 Office→PDF Conversion
LibreOffice kit. Targets mit native engine nutzen sie; andere Node WASM.  
**QOC-Bezug:** QOC. Lernpunkt: **Document-Conversion** als Capability.

---

## 34. SSH (code-level)

### 34.1 Remote Providers via Shared Connection
Filesystem/Subprocess/Sandbox über SSH. Provider-owned paths.  
**QOC-Bezug:** QOC hat keine SSH-Execution. Lernpunkt: **Remote Execution Providers**.

---

## 35. Terminal (code-level)

### 35.1 Persistent PTY
Interactive shells/REPLs über Tool-Calls hinweg. Owner-isolated. Nicht restart-surviving.  
**QOC-Bezug:** QOC hat keine persistenten Terminals. Lernpunkt: **Persistent Terminal** als Capability.

---

## 36. LSP (code-level)

### 36.1 Code Navigation
Go-to-definition, find-references, hover. Provider choice transparent.  
**QOC-Bezug:** QOC. Lernpunkt: **LSP-Integration**.

---

## 37. Web (code-level)

### 37.1 Search/Fetch Providers
Exa/Perplexity/DeepSeek für Search, anonymous HTTP(S)/DeepSeek für Fetch.  
**QOC-Bezug:** QOC. Lernpunkt: **Web-Access als Capability** (QOC hat Firecrawl-MCP).

---

## 38. Browser-use / Computer-use (code-level)

### 38.1 Exclusive Provider Registration
Nur ein Provider pro Composition.  
**QOC-Bezug:** QOC. Lernpunkt: **Exclusive Provider Pattern**.

---

## 39. Context (code-level)

### 39.1 Request-Context Plugins
Agent-instructions, @file mentions, session references, time, tmux location. Durable (enter session history as user-role messages).  
**QOC-Bezug:** QOC hat ContextBuilder. Lernpunkt: **Context als Plugins** mit Durable-Injection.

---

## 40. FS (code-level)

### 40.1 Write Intents
`createIfAbsent`, `replaceIfVersion`. Omitting = unconditional create-or-overwrite.  
**QOC-Bezug:** QOC. Lernpunkt: **Guarded Writes**.

### 40.2 Move Outcome
`FsMoveOutcome = { sourceBefore, destination, sourceDeleted: true } | { ..., sourceDeleted: false, sourceFailure }`. Destination published even if source removal fails.  
**QOC-Bezug:** QOC. Lernpunkt: **Move mit Partial-Failure-Reporting**.

### 40.3 Patch Hunks
`FS_PATCH_MISMATCH` für Patch-Failures.  
**QOC-Bezug:** QOC. Lernpunkt: **Patch-Hunk-Support**.

### 40.4 Path-Level Symlink Probe
`FsPathInfo` mit `type: 'symlink'` für Trust-Boundary-Checks vor Resolve.  
**QOC-Bezug:** QOC. Lernpunkt: **Symlink-Detection vor Resolve**.

---

## 41. Konkrete Übernahme-Empfehlungen für QOC (priorisiert nach Impact)

Bewertungsskala: **Impact** 1–5 (Produktwert), **Aufwand** 1–5 (Entwicklungszeit), **Integrierbarkeit** (ohne Architekturbruch: hoch/mittel/niedrig).  
Architekturbruch = Änderung an `engine.core`-Grenzen, Port-Prinzip, Paket-Sichtbarkeit oder ArchUnit-Regeln.

### 41.1 Priorisierungsmatrix (P0/P1/P2)

| # | Empfehlung | Impact | Aufwand | Integr. | Kategorie |
|---|---|---|---|---|---|
| 1 | Empty-Session-Purge (>2h) | 3 | 1 | hoch | ✅ erledigt |
| 2 | Fail-Closed Approval (missing answerer → `unavailable`) | 4 | 1 | hoch | P0 |
| 3 | Symlink-Safe Writes (O_EXCL, 0600) | 4 | 1 | hoch | P0 |
| 4 | Canonical Path Normalization (realpath) | 3 | 1 | hoch | P0 |
| 5 | Branded Types für Seq/Offset/Version-Tokens | 3 | 2 | hoch | P0 |
| 6 | Typed FS-Error-Codes (wie FailureKind) | 3 | 2 | hoch | P0 |
| 7 | Capability Flags für Subagents | 3 | 1 | hoch | P0 |
| 8 | Depth Limit für Subagents | 4 | 1 | hoch | P0 |
| 9 | Empty-is-Absent (Credentials/Settings) | 3 | 1 | hoch | P0 |
| 10 | Repeat-Tool-Reminder + Timeout-Policy (Guards) | 4 | 3 | hoch | P0 |
| 11 | Per-Session Approval-Policy (`ask`/`never`) als Event | 4 | 3 | mittel | P0 |
| 12 | Audit-Pair (`approval/asked`+`decided`) mit Request-ID | 4 | 3 | mittel | P0 |
| 13 | Credential References + Per-Operation Resolve | 5 | 4 | mittel | P1 |
| 14 | FsVersion Stale-Guards + createIfAbsent | 4 | 3 | mittel | P1 |
| 15 | FsWriteOutcome before/after für Diff/Revert | 4 | 2 | hoch | P1 |
| 16 | Fs Move mit Partial-Failure-Reporting | 3 | 2 | hoch | P1 |
| 17 | Pinned Session Titles + Model-Identity + Bounded Aggregate | 3 | 2 | hoch | P1 |
| 18 | Optimistic Concurrency (Revision) für Settings | 3 | 3 | mittel | P1 |
| 19 | Path-addressed Settings Edits (set/unset) | 3 | 3 | mittel | P1 |
| 20 | Secret Redaction im Wire | 4 | 2 | mittel | P1 |
| 21 | Opaque Spill Locators + Best-Effort Policy | 4 | 3 | mittel | P1 |
| 22 | Tool-Result-Pruning vor Compaction | 4 | 3 | mittel | P1 |
| 23 | Compaction Lock-Events (Crash-Safety) | 4 | 3 | mittel | P1 |
| 24 | Surface/Shadowed/Log-only Klassifikation | 4 | 4 | mittel | P1 |
| 25 | System-Prompt Sections mit Order + Waterfall-Assembly | 3 | 2 | hoch | P1 |
| 26 | Per-Session Serialization (Directory-Mutations) | 3 | 2 | hoch | P1 |
| 27 | Capability Flags für Subagents (erweitert) | 3 | 2 | hoch | P1 |
| 28 | Empty-is-Absent für Provider-Config | 3 | 1 | hoch | P1 |
| 29 | Canonical Path Normalization (erweitert) | 3 | 2 | hoch | P1 |
| 30 | Capability Seams formalisieren | 5 | 5 | niedrig | P2 |
| 31 | Sandbox Modes + Enforcement | 4 | 5 | niedrig | P2 |
| 32 | PTC (Programmatic Tool Calling) | 3 | 5 | niedrig | P2 |
| 33 | Deliverables (present + Workspace Changes) | 3 | 4 | mittel | P2 |
| 34 | Background Jobs (Session-Lifecycle-Records) | 4 | 4 | mittel | P2 |
| 35 | Scheduling (7 Timing Kinds) | 3 | 5 | niedrig | P2 |
| 36 | Goals (Revision-basiert) | 3 | 4 | mittel | P2 |
| 37 | Plan Mode (Soft Guidance) | 3 | 4 | mittel | P2 |
| 38 | Session Projections (detached Readers) | 4 | 4 | mittel | P2 |
| 39 | Session Query (cross-corpus, surface classification) | 3 | 4 | mittel | P2 |
| 40 | Working Directory als Event + Sandbox-Root-Anker | 4 | 3 | mittel | P2 |
| 41 | Workspace (Canonical Paths, Membership, Activity) | 3 | 4 | mittel | P2 |
| 42 | MCP Scope-basiert + Instructions in System Prompt | 3 | 3 | mittel | P2 |
| 43 | Adjacent Migrations für Event-Log-Format | 4 | 5 | niedrig | P2 |
| 44 | Image-Request-Caching (Variant-basiert) | 3 | 3 | mittel | P2 |
| 45 | Image Offload Events | 3 | 3 | mittel | P2 |
| 46 | Terminal (persistent PTY) | 2 | 4 | niedrig | P2 |
| 47 | LSP Integration | 2 | 4 | niedrig | P2 |
| 48 | SSH Remote Execution Providers | 2 | 5 | niedrig | P2 |
| 49 | Web-Access Capability | 2 | 3 | mittel | P2 |
| 50 | Document Conversion (Office→PDF) | 2 | 3 | mittel | P2 |
| 51 | Browser/Computer-use (Exclusive Provider) | 2 | 5 | niedrig | P2 |
| 52 | Context als Plugins (Durable Injection) | 3 | 4 | mittel | P2 |
| 53 | Anonymous Installation Identity | 2 | 2 | hoch | P2 |
| 54 | OTEL Integration | 3 | 3 | mittel | P2 |

---

### 41.2 P0 — Detailbeschreibungen

#### 2. Fail-Closed Approval (Impact 4, Aufwand 1, Integr. hoch)
**Was:** Wenn kein Answerer verfügbar ist oder wirft, wird die Approval als `unavailable` behandelt (nicht geöffnet).  
**Warum:** Verhindert, dass ein fehlender UI-Kanal zu einem stillen `allowed`.  
**Integration:** Nur `WebApprovalSource`/`DurableApprovalSource` anpassen: bei fehlendem Answerer `ToolOutcome`/`StepResult` als denied zurückgeben. Kein Architekturbruch — rein in der Adapter-Schicht.  
**Aufwand:** 1 Tag (Code + Tests).

#### 3. Symlink-Safe Writes (Impact 4, Aufwand 1, Integr. hoch)
**Was:** Filesystem-Writes mit `O_EXCL` (create) und Mode 0600, um Symlink-Attacks zu verhindern.  
**Warum:** Sicherheit bei Agent-gesteuerten Filesystem-Operationen.  
**Integration:** `JdkFileSystem`/`PosixFileSystem` anpassen: `Files.createFile` mit `CREATE_NEW` + `posix:permissions`. Kein Port-Bruch.  
**Aufwand:** 1 Tag.

#### 4. Canonical Path Normalization (Impact 3, Aufwand 1, Integr. hoch)
**Was:** `realpath`-Normalisierung (resolve symlinks, `..`, trailing slashes) als einzige Uniqueness-Canon.  
**Warum:** Verhindert Path-Confusion und Duplicate-Workspace-Einträge.  
**Integration:** In `Filesystem`-Adapter und `WorkspaceStore` (falls vorhanden). QOC hat kein Workspace-Konzept, aber Filesystem-Tools profitieren.  
**Aufwand:** 1 Tag.

#### 5. Branded Types für Seq/Offset/Version-Tokens (Impact 3, Aufwand 2, Integr. hoch)
**Was:** `SessionSeq`, `SessionLogOffset`, `FsVersion`, `FsTargetKey` als gebrandete Typen einführen (Java: Records mit Validierung oder Value-Objects).  
**Warum:** Verhindert Verwechslung von Seq vs. Offset vs. Instant zur Compile-Zeit.  
**Integration:** Nur neue Typen in `engine.core`, keine bestehenden Signaturen ändern (additive).  
**Aufwand:** 2–3 Tage (Typen + Migration der Aufrufer).

#### 6. Typed FS-Error-Codes (Impact 3, Aufwand 2, Integr. hoch)
**Was:** `FsErrorCode`-Enum (`FS_NOT_FOUND`, `FS_STALE_VERSION`, `FS_AMBIGUOUS_EDIT`, …) analog zu `FailureKind`.  
**Warum:** Strukturierte Fehlerbehandlung in Tools/UI.  
**Integration:** Neues Enum in `engine.core`, `FsError`-Exception. Tools werfen typisierte Codes.  
**Aufwand:** 2 Tage.

#### 7. Capability Flags für Subagents (Impact 3, Aufwand 1, Integr. hoch)
**Was:** `SubagentCapabilities { agentOptions, agentPreset, outputSchema, depthLimit }` als Flags am Provider.  
**Warum:** Verhindert, dass ein Provider Optionen akzeptiert, die er nicht kann.  
**Integration:** In `A2AClient`/`McpClient`-Adapter als Capability-Flags. Kein Port-Bruch.  
**Aufwand:** 1 Tag.

#### 8. Depth Limit für Subagents (Impact 4, Aufwand 1, Integr. hoch)
**Was:** `maxDepth` Cap für rekursive Subagent-Aufrufe.  
**Warum:** Verhindert unendliche Rekursion (Kosten-/Stabilitätsrisiko).  
**Integration:** In `agent_create`/A2A-Client als Parameter + Check.  
**Aufwand:** 1 Tag.

#### 9. Empty-is-Absent (Impact 3, Aufwand 1, Integr. hoch)
**Was:** Leerer String/Null = absent (Provider-URL, API-Key, Config-Werte).  
**Warum:** Verhindert, dass leere Config-Werte als gültige Werte behandelt werden.  
**Integration:** Validierung in `ProviderProducer`/Config-Loading.  
**Aufwand:** 1 Tag.

#### 10. Repeat-Tool-Reminder + Timeout-Policy (Impact 4, Aufwand 3, Integr. hoch)
**Was:** Guard, der wiederholte identische Tool-Calls erkennt und dem Modell eine Erinnerung injiziert; Timeout-Policy für Tools mit deklariertem Timeout.  
**Warum:** Verhindert stuck loops und hängende Calls.  
**Integration:** Als `ToolHook`/`Step`-Phase (pre-execute). QOC hat `ToolHooks` — dort einbauen. Kein Architekturbruch.  
**Aufwand:** 3–4 Tage.

#### 11. Per-Session Approval-Policy `ask`/`never` (Impact 4, Aufwand 3, Integr. mittel)
**Was:** `ApprovalPolicy = 'ask' | 'never'` als per-Session Event. `never` gibt deterministisch `rejected`. Effective value = letztes `approval/policy` Event, Fallback Service-Config.  
**Warum:** Headless/CI-Betrieb ohne Prompts; replayable.  
**Integration:** Neues Session-Event `approval/policy` + `SessionStore`/`Journal`. Medium, weil Event-Schema + Replay.  
**Aufwand:** 3–4 Tage.

#### 12. Audit-Pair mit Request-ID (Impact 4, Aufwand 3, Integr. mittel)
**Was:** `approval/asked` + `approval/decided` als log-only Pair, korreliert über `ApprovalRequestId`.  
**Warum:** Auditierbarkeit von Approvals.  
**Integration:** Neue Events + Store. Medium.  
**Aufwand:** 3 Tage.

---

### 41.3 P1 — Detailbeschreibungen

#### 13. Credential References + Per-Operation Resolve (Impact 5, Aufwand 4, Integr. mittel)
**Was:** Settings enthalten nur Referenzen (Env-Var-Namen), Provider besitzt Werte. Resolve pro Operation (kein Cache) → Hot-Rotation.  
**Warum:** Höchster Impact: Secrets aus Config raus, Rotation ohne Neustart.  
**Integration:** `ProviderProducer` und Config-Loading anpassen: statt `apiKey`-String eine `CredentialRef` auflösen. Medium-Hoch, weil Config-Schema und UI betroffen. Kein Port-Bruch (Adapter-Schicht).  
**Aufwand:** 1 Woche.

#### 14. FsVersion Stale-Guards (Impact 4, Aufwand 3, Integr. mittel)
**Was:** `replaceIfVersion(version)` / `createIfAbsent` Intents im Filesystem.  
**Warum:** Verhindert Lost-Update bei concurrent Edits.  
**Integration:** `FileSystem`-Port erweitern (neue Methoden/Parameter). Port-Änderung, aber additiv.  
**Aufwand:** 3–4 Tage.

#### 15. FsWriteOutcome before/after (Impact 4, Aufwand 2, Integr. hoch)
**Was:** Write/Edit/Delete-Ergebnisse tragen `before`/`after` (LF-normalisiert).  
**Warum:** UI-Diff und Revert ohne Re-Read.  
**Integration:** Return-Types in `FileSystem`-Port erweitern. Additiv.  
**Aufwand:** 2 Tage.

#### 16. Fs Move Partial-Failure (Impact 3, Aufwand 2, Integr. hoch)
**Was:** Move publiziert Destination auch wenn Source-Removal fehlschlägt; `sourceDeleted: false + sourceFailure`.  
**Warum:** Korrektes Error-Reporting bei partial failures.  
**Integration:** `move`-Methode im Port.  
**Aufwand:** 2 Tage.

#### 17. Pinned Session Titles (Impact 3, Aufwand 2, Integr. hoch)
**Was:** User-Rename pinnt Title (stopt Auto). Model-Identity-Tracking. `TitleInputState` als bounded Aggregate `{ first, count, lastSeq }`.  
**Warum:** Konsistentes Title-Verhalten, keine Überschreibung durch Auto-Title nach User-Rename.  
**Integration:** `TitleGenerator` + `SessionStore` anpassen.  
**Aufwand:** 2 Tage.

#### 18. Optimistic Concurrency für Settings (Impact 3, Aufwand 3, Integr. mittel)
**Was:** `revision` monotonic; write mit `expectedRevision`; stale refused.  
**Warum:** Verhindert lost updates bei concurrent Settings-Edits.  
**Integration:** `SettingsStore` + UI.  
**Aufwand:** 3 Tage.

#### 19. Path-addressed Settings Edits (Impact 3, Aufwand 3, Integr. mittel)
**Was:** `set(path, value)` / `unset(path)` statt whole-replace.  
**Warum:** Granulare Edits, Secret-Preservation.  
**Integration:** Settings-API erweitern.  
**Aufwand:** 3 Tage.

#### 20. Secret Redaction im Wire (Impact 4, Aufwand 2, Integr. mittel)
**Was:** Secrets nie im Wire; `SettingsSecretView { path, set }`.  
**Warum:** Sicherheit.  
**Integration:** UI/REST-API anpassen.  
**Aufwand:** 2 Tage.

#### 21. Opaque Spill Locators (Impact 4, Aufwand 3, Integr. mittel)
**Was:** `SpillLocator` branded/opaque; Consumers rendern mit `retrievalHint`, parsen nicht.  
**Warum:** Entkopplung von Storage-Backend.  
**Integration:** QOC's `ToolResultCache`/ArtifactRef anpassen.  
**Aufwand:** 3 Tage.

#### 22. Tool-Result-Pruning (Impact 4, Aufwand 3, Integr. mittel)
**Was:** Große Tool-Results durch head/tail + Spill-Locator ersetzen (best-effort).  
**Warum:** Kontext-Ersparnis.  
**Integration:** In `CompactionService` oder `Step` (pre-request).  
**Aufwand:** 3 Tage.

#### 23. Compaction Lock-Events (Impact 4, Aufwand 3, Integr. mittel)
**Was:** `compaction/start` (Lock) → Summary → `compaction/end` (Release). Crash = detectable orphaned lock.  
**Warum:** Crash-Safety für Compaction.  
**Integration:** `CompactionService` + `Journal`.  
**Aufwand:** 3 Tage.

#### 24. Surface/Shadowed/Log-only (Impact 4, Aufwand 4, Integr. mittel)
**Was:** Event-Klassifikation `current | shadowed | log-only`. `foldSurface()` berechnet modell-sichtbare Sicht.  
**Warum:** Korrektes Replay nach Compaction.  
**Integration:** `ConversationFolding` + `Journal` erweitern.  
**Aufwand:** 4 Tage.

#### 25. System-Prompt Sections mit Order (Impact 3, Aufwand 2, Integr. hoch)
**Was:** Prompt-Sections mit Order (z. B. `plan:policy` bei 50). Waterfall-Assembly.  
**Warum:** Flexiblere Prompt-Komposition.  
**Integration:** `ContextBuilder` anpassen.  
**Aufwand:** 2 Tage.

#### 26. Per-Session Serialization (Impact 3, Aufwand 2, Integr. hoch)
**Was:** Per-Session Promise-Chain für ordered Mutations (z. B. Directory-Changes).  
**Warum:** Verhindert Race-Conditions bei concurrent Mutations.  
**Integration:** In `SessionService` oder `ToolRuntime`.  
**Aufwand:** 2 Tage.

#### 27. Capability Flags (erweitert) (Impact 3, Aufwand 2, Integr. hoch)
**Was:** Capability-Flags am Provider (agentOptions, agentPreset, outputSchema, depthLimit).  
**Warum:** Provider-Fähigkeiten explizit.  
**Integration:** In `ProviderAdapter`/`ModelInfo`.  
**Aufwand:** 2 Tage.

#### 28. Empty-is-Absent (erweitert) (Impact 3, Aufwand 1, Integr. hoch)
**Was:** Leere Werte = absent (Provider-URL, API-Key, Config).  
**Warum:** Konsistenz.  
**Integration:** Config-Loading + ProviderProducer.  
**Aufwand:** 1 Tag.

#### 29. Canonical Path Normalization (erweitert) (Impact 3, Aufwand 2, Integr. hoch)
**Was:** realpath-Normalisierung (symlinks, `..`, trailing slashes).  
**Warum:** Path-Confusion verhindern.  
**Integration:** Filesystem-Adapter + Workspace (falls vorhanden).  
**Aufwand:** 2 Tage.

---

### 41.4 P2 — Nur Kurzreferenz (hoher Aufwand / niedrige Integrationsreife)

Sandbox Modes, PTC, Deliverables, Background Jobs, Scheduling, Goals, Plan Mode, Session Projections, Session Query, Working Directory als Event, Workspace, MCP Scope, Adjacent Migrations, Image-Caching, Image Offload, Terminal, LSP, SSH, Web-Access, Document Conversion, Browser/Computer-use, Context-as-Plugins, Anonymous Identity, OTEL, Capability Seams formalisieren.

Diese erfordern entweder neue Subsysteme oder tiefere Architekturänderungen und sind erst sinnvoll, wenn P0/P1 abgeschlossen sind.

---

### 41.5 Nicht übernehmen (QOC-spezifisch anders gelöst)
- Plugin-Architektur (Cordis) — QOC ist Java/Quarkus mit festem Kern; Ports reichen.
- TypeScript/Node-Ökosystem — andere Runtime.
- Web-UI-Details — QOC hat eigene UI.
- Session-Format v4 Details — QOC nutzt DB, nicht JSONL.
- Capability Seams als Plugin-System — QOC nutzt Port-Prinzip.

## 42. Referenzen

- `deepseek-harness/docs/architecture.md`
- `deepseek-harness/docs/agent-lifecycle.md`
- `deepseek-harness/docs/tool-execution-pipeline.md`
- `deepseek-harness/docs/session-format-status.md`
- `deepseek-harness/docs/subsystems/*.md`
- `deepseek-harness/packages/*/README.md`
- `deepseek-harness/packages/*/src/*.ts` (Typen und Implementierungen)
