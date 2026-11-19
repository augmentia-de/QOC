# Learnings from `deepseek-harness` for QOC (code-based)

> Source: `/home/torsten/dev/my-projects/deepseek-harness` (Cordis-based Agent Harness, TS/Node).
> Method: Analysis of subsystem docs **and** source types (`packages/*/src/*.ts`).
> Goal: What QOC can or should adopt from this system — architecture, patterns, features, invariants.
> Status: 2026-11-02 · Analysis, no code change.

---

## 1. Context

`deepseek-harness` (dsh) is a plugin-based agent harness built on **Cordis** (plugin framework: services, events, reversible effects). Everything is a plugin — model adapter, tool registry, session log, agent loop. QOC is a Java/Quarkus engine with a fixed core. The learnings are therefore **conceptual**: patterns, data models, invariants that QOC can adopt or retrofit without copying the plugin architecture.

---

## 2. Type system & invariants (code-level)

### 2.1 Branded types everywhere
dsh brands **every** ID and numeric cursor: `SessionId`, `SessionSeq`, `SessionLogOffset`, `FsTargetKey`, `FsVersion`, `ApprovalRequestId`, `WorkspaceId`, `GoalId`, `JobId`, `CredentialRef`, `CredentialKey`, `SpillLocator`, `AttachmentId`, `SubagentPromptRequestId`, `SessionTitleProviderId`, `GoalRef`.
Numeric seqs are branded with validation (`SessionSeq`: non-negative safe integer, no `-0`).
**QOC relevance:** QOC has `SessionId`, `CallId`, `ToolName`, `MessageId`, but **no branded seq/cursor/version tokens**. Learning: **introduce branded types for seq, offset, version tokens** to prevent confusion (e.g. `updated()` instant vs. seq) at compile time. Especially `FsVersion` and `FsTargetKey` as opaque tokens.

### 2.2 Opaque targets/versions in the filesystem
`FsTargetKey` (opaque key, backend determines representation), `FsVersion` (opaque freshness token). Consumers **must not parse**. `FsObservation = present(version) | absent`. `FsWriteIntent = createIfAbsent | replaceIfVersion(version)`.
**QOC relevance:** QOC's filesystem tools have no version-based stale guards. Learning: **introduce version-based stale guards** (`replaceIfVersion`) + `createIfAbsent` (atomic create). Prevents lost updates on concurrent edits.

### 2.3 FsWriteOutcome with before/after
`FsWriteOutcome` carries `before: string | null` (LF-normalized, before the write) and `after: string`. `FsEditOutcome` carries `before`/`after`. `FsDeleteOutcome` carries `before`.
**QOC relevance:** QOC's edit tool returns only success/failure. Learning: **return before/after in the result**, so UI/diff/revert work without re-reading. QOC has revert staging — it would benefit from this.

### 2.4 Typed FsError codes
`FS_ERROR_CODES` = 15 stable codes (`FS_NOT_FOUND`, `FS_STALE_VERSION`, `FS_AMBIGUOUS_EDIT`, `FS_PATCH_MISMATCH`, …). `FsError extends HarnessError`.
**QOC relevance:** QOC has `FailureKind` for providers, but **no typed FS error codes**. Learning: **introduce an FS error taxonomy** (analogous to `FailureKind` for providers).

### 2.5 Empty-is-absent (credentials)
An empty stored value = absent everywhere. `CredentialInfo { configured, source?, writable }`.
**QOC relevance:** QOC's settings/provider config. Learning: **apply the empty-is-absent rule consistently** (provider URL, API key).

---

## 3. Architectural principles

### 3.1 Capability seams (service definition / provider / consumer)
Every capability is a triad: service definition (interface), service provider (impl), consumer (e.g. tool). Swapping a provider changes the whole product.
**QOC relevance:** QOC has ports (`ProviderAdapter`, `HttpTransport`, `SessionStore`). Learning: **define every** external capability (spill, compaction, sandbox, approval, credentials, storage) as an explicit seam with three roles.

### 3.2 "Model-visible means logged"
Every model request must be reconstructable from the log. New model-visible inputs require session events.
**QOC relevance:** QOC has an event log. Learning: **strictly enforce** that no model-visible state lives only in memory. Runtime context (working directory, approval policy, plan mode) must be an event in the log.

### 3.3 Pure message projections
Plugins change existing message content via **pure projections** (no mutation); detached readers return the same definitions.
**QOC relevance:** QOC's `ConversationFolding`. Learning: **projections as a first-class concept**.

### 3.4 Capability events (fs/*, tools/*, telemetry/*)
Policy/hooks intervene via events without importing the loop.
**QOC relevance:** QOC has no capability events. Learning: introduce `fs/*`, `tools/*`, `telemetry/*` as event domains.

### 3.5 Working directories as their own layer
The active directory is an event (`working-directory/change`), immutable header identifies the original project. Sandbox write roots stay anchored to the original.
**QOC relevance:** Learning: **sandbox roots must not travel with cwd** — an important security invariant.

---

## 4. Session log & replay

### 4.1 Adjacent migrations (immutable generations)
Session format versions as `vN` files, never renamed/overwritten/deleted. `stat`/`list` pick the highest canonical generation; `open` decodes the migration chain. Migration only inserts a missing `turn/end` if a later `turn/start` already exists. `SESSION_FORMAT_VERSION = 4`.
**QOC relevance:** QOC has Flyway for DB schema, but **no format versioning for the event log**. Learning: if QOC changes the persisted event format, use immutable generations + adjacent migrations.

### 4.2 Surface vs. shadowed vs. log-only
Every event is `current` (model-visible), `shadowed` (replaced by compaction) or `log-only` (audit). `foldSurface()` computes the model-visible view. `SessionEventSurface = 'current' | 'shadowed' | 'log-only'`.
**QOC relevance:** QOC has no surface classification. Learning: **surface classification** as a first-class concept.

### 4.3 Session projections (fold committed events)
Domain plugins register "units" that fold committed events; carriers read finished values. Registry subscribes once to `session/event`. `ProjectionHandle` keeps readers even after unregistration (`stateOf`, `readerFor`, `dispose`). Summary-excluded keys (`SessionProjectionSummaryExcluded`).
**QOC relevance:** QOC's `ConversationFolding`. Learning: **projections as a service** with detached readers.

### 4.4 Session query (cross-corpus)
`SessionRecord { header, live, persisted, formatStatus? }`. `SessionLogSnapshot` (detached, replay-validated). `SessionSurfaceSnapshot` (exact-read). Filter + relationship tracing.
**QOC relevance:** QOC has `SessionStore` + `Journal`. Learning: **unified query layer** over live + persisted.

### 4.5 Session title (latest-wins, pinned)
`SessionTitleSource = fallback | provider (with ModelIdentity) | user`. User rename pins (stops auto). `TitleInputState` as a bounded aggregate: `{ first, count, lastSeq }`.
**QOC relevance:** QOC has `TitleGenerator`. Learning: **pinning semantics** + **model-identity tracking** + **bounded aggregate** for title input.

### 4.6 Branded SessionSeq/SessionLogOffset
`SessionSeq` = non-negative safe integer branded. `SessionSeqCursor = SessionSeq | -1`. `OptionalSessionSeq = SessionSeq | null`.
**QOC relevance:** QOC uses `Seq` (long). Learning: **type safety** for seq vs. offset vs. cursor.

---

## 5. Turn/step lifecycle

### 5.1 Claiming + pre-step waterfall
Turn opens, claims next-step input + a queued message. `agent/pre-step` is a waterfall (accept/rewrite/reject). Rejected/empty first claim closes the turn without a step. `startsRequestSeries` declaration.
**QOC relevance:** QOC has `Step` + `StepResult`. Learning: **pre-step hook as a waterfall** for context injection, steering, policy checks.

### 5.2 Prepare-call with cancellation semantics
`prepareCall()` binds the adapter; cancellation during the async phase commits neither system nor users.
**QOC relevance:** QOC's `Step.run`. Learning: **document and test the cancellation-commitment guarantee**.

### 5.3 Freeze request from log
Request is derived from the log and frozen (immutable). Retry uses freeze evidence. Loop-built requests are **deep-frozen**.
**QOC relevance:** QOC has `ModelRequest`. Learning: **request freeze as an explicit concept**.

### 5.4 request/header & request/context events
`request/header` (model-selection snapshot) and `request/context` (runtime facts) as separate durable events.
**QOC relevance:** QOC has `ModelRequest` + `GenerationOptions`. Learning: **model-selection snapshot per request** as an event.

### 5.5 Retry without re-assembly
Retry does not re-run assembly/pre-step/user admission, only prepare + reconcile.
**QOC relevance:** QOC has no retry logic in the provider (only `FailureKind`). Learning: **document retry semantics**.

### 5.6 turn-stopping (serial, terminal checkpoint)
`agent/turn-stopping` is serial (no `next()`), terminal checkpoint before `turn/end`.
**QOC relevance:** QOC has `TurnOutcome`. Learning: **terminal checkpoint hook**.

### 5.7 Failed steps record missing tool results
Failed steps log missing tool results.
**QOC relevance:** QOC's `StepResult.Failed`. Learning: **invariant test**.

### 5.8 Claimed batch stays removed on reject
On rejected/empty first claim the claimed batch stays removed; the turn emits no step.
**QOC relevance:** QOC. Learning: **claimed-input semantics** on rejection.

---

## 6. Tool-execution pipeline

### 6.1 Pre/execute/post waterfalls
`tools/pre-execute` (hooks, permission, sandbox), `tools/execute` (timeout, retry, metrics around dispatch), `tools/post-execute` (accept/block/replace/add context). Monotonic guards (deny/abstain) run between pre and execute. Guard throws → normalized.
**QOC relevance:** QOC has permission gate + `ToolRuntime`. Learning: **three-phase pipeline** as explicit design.

### 6.2 Approval before guards
`ctx.approval` resolves asks **before** monotonic guards. Owner policy that must not be reorderable stays a guard.
**QOC relevance:** QOC has HITL/ASK. Learning: **document ordering**.

### 6.3 fs/write-intent & fs/edit-intent
Tool FS mutations go through `fs/*` events (read-before-edit checks).
**QOC relevance:** QOC has filesystem tools. Learning: **intent events** for FS mutations.

### 6.4 projectContent / finalizeContent
`ToolDefinition.projectContent` installs prepared content before post-execute; `finalizeContent` is the last content-only invariant. Pipeline snapshots the candidate result and normalizes snapshot failure.
**QOC relevance:** QOC's tool-result handling. Learning: **two-stage content transformation**.

### 6.5 additionalContexts (FIFO)
After recorded tool results, additionalContexts are injected as `user/message`.
**QOC relevance:** QOC. Learning: **post-tool context injection**.

### 6.6 PTC mode (programmatic tool calling)
`run_code` transport + sub-calls through the same pipeline; sub-calls carry a parent token, log `tool/ptc-dispatch`, denials are binding rejections, no additionalContexts.
**QOC relevance:** QOC has no PTC. Learning: **PTC as an optional capability**.

### 6.7 presentCall / presentResult (UI cards)
UI pending/completed cards as hooks.
**QOC relevance:** QOC has UI. Learning: **decouple tool UI hooks**.

### 6.8 Timeout policy & repeat-tool reminder (guards)
`guard/` family: repeat-tool-reminder + timeout-policy. Both ship enabled in the base bundle.
**QOC relevance:** QOC has no loop guards. Learning: **loop-hygiene guards** (repeat detection, timeout).

---

## 7. Compaction (code-level)

### 7.1 Lock brackets (start/end)
`compaction/start` (lock) → summarization → `compaction/summary` → `user/message` (replace) → `compaction/end` (release). Lock first, release last → crash mid-op = detectable orphaned lock.
**QOC relevance:** QOC has `CompactionService`. Learning: **lock-event pattern**.

### 7.2 Surface replacement via surfaceOp
Summary replaces the surface range via `surfaceOp: { op: 'replace', startSeq, endSeq }` on `user/message`. Shadowed seqs are tracked. `shadowedRange` is a position span, not a numeric interval (after a prior replace, start can exceed end).
**QOC relevance:** QOC's `ConversationFolding`. Learning: **surface op as an explicit event field**.

### 7.3 Tool-result pruning before summary
Optional `ctx.toolResultPruner` replaces large tool results with head/tail + spill locator. Runs before range selection, re-measured via tokenMeter. `PrunedEntry { originalSeq, replacementSeq, callId, charsBefore, charsAfter }`.
**QOC relevance:** QOC has `ToolResultCache`. Learning: **tool-result pruning as a separate capability**.

### 7.4 Image offload
`image/offload` event with `seq` + `imageIndexes` (depth-first). Persist-before-event.
**QOC relevance:** QOC has `ModelContent.Media`. Learning: **image-offload event**.

### 7.5 Trigger classes
`CompactionTrigger = 'pressure' | 'context-overflow'`; overflow is more aggressive.
**QOC relevance:** QOC's compaction. Learning: **two trigger classes**.

### 7.6 Manual error codes
`ManualCompactionErrorCode = 'busy' | 'cancelled' | 'changed' | 'summary' | 'commit' | 'persistence'`.
**QOC relevance:** QOC. Learning: **granular error codes**.

### 7.7 Tool-pairing boundaries
`toolPairingBalancedBefore(session, seq)` and `toolPairingBalancedAfter(session, seq)` — validate surface membership, reject missing seqs and orphan results.
**QOC relevance:** QOC. Learning: **tool-call/result pairing as an invariant**.

### 7.8 CompactionId (stable identity)
`CompactionId` as stable identity for the entire durable lifecycle. `compactCheckpointSource(compactionId, sourceCommandId?)` for the replacement checkpoint.
**QOC relevance:** QOC. Learning: **compaction identity** for correlation.

---

## 8. Spill storage (code-level)

### 8.1 Opaque locators
`SpillLocator` branded/opaque; local backend uses a path, remote uses URI/key. Consumers render with `retrievalHint`, do not parse. `SpillRef { locator, bytes, retrievalHint }`.
**QOC relevance:** QOC has `ToolResultCache` + artifact refs. Learning: **opaque locators**.

### 8.2 Best-effort policy
Spill policy replaces results over `maxInlineTokens` with head/tail + spill address; on save failure the original stays inline.
**QOC relevance:** QOC. Learning: **best-effort spill**.

### 8.3 Symlink safety
`open(path, 'wx', 0o600)` — exclusive write, 0600. Session subdir via `sha256(sessionId)`.
**QOC relevance:** QOC's filesystem tools. Learning: **symlink-safe writes**.

### 8.4 Retention
Spill follows session retention.
**QOC relevance:** QOC's `ToolOutputStore` sweep. Learning: **couple spill retention to the session**.

### 8.5 SaveTextSpill request
`{ owner: { sessionId }, source: tool|session-reference, suggestedName, content }`. `source` describes the producer, never access control.
**QOC relevance:** QOC. Learning: **spill request with owner/source separation**.

---

## 9. Subagents (code-level)

### 9.1 Capability flags
`SubagentCapabilities { agentOptions, agentPreset, outputSchema, depthLimit }`. Manager checks before prepare.
**QOC relevance:** QOC has `agent_create`. Learning: **capability flags**.

### 9.2 Activation vs. prepareContinuable
`startActivation` unified; `prepareContinuable` for local child agents, `start` for external.
**QOC relevance:** QOC's A2A/MCP. Learning: **unified activation API**.

### 9.3 Delivery modes
`delivery: 'parent' | 'caller'`.
**QOC relevance:** QOC. Learning: **delivery mode**.

### 9.4 Structured output schema
`outputSchema` as an object JSON schema; child returns a `structured` value.
**QOC relevance:** QOC has `SubmitResult`. Learning: **structured output for subagents**.

### 9.5 Depth limit
`maxDepth` cap; computed depth must be ≤ cap.
**QOC relevance:** QOC. Learning: **recursion protection**.

### 9.6 cwd inheritance
Child inherits the parent's effective directory at admission; relative paths resolve against the parent.
**QOC relevance:** QOC's ephemeral sessions. Learning: **cwd inheritance**.

### 9.7 Catalog rows (one-shot/external/continuable)
`SubagentCatalogRow` with `mode: 'one-shot' | 'external' | 'continuable'`. Historical children with unavailable descriptors remain `mode: 'unknown'`.
**QOC relevance:** QOC's A2A agent cards. Learning: **mode classification** for subagents.

### 9.8 SubagentPromptRequest (queue/steer)
`delivery: 'queue' | 'steer'` for messages to continuable children.
**QOC relevance:** QOC. Learning: **steering vs. queuing**.

---

## 10. Approval (code-level)

### 10.1 Per-session policy (ask/never)
`ApprovalPolicy = 'ask' | 'never'`; `never` deterministically returns `rejected`. Effective value = latest `approval/policy` event, fallback service config.
**QOC relevance:** QOC has ASK + permission gate. Learning: **per-session approval policy as an event**.

### 10.2 Fail-closed
`ApprovalOutcome = 'allowed-once' | 'rejected' | 'cancelled' | 'unavailable'`. Missing/throwing answerer → `unavailable`.
**QOC relevance:** QOC. Learning: **fail-closed semantics**.

### 10.3 Audit events (log-only)
`approval/asked` + `approval/decided` as a log-only pair, correlated via `ApprovalRequestId`.
**QOC relevance:** QOC. Learning: **audit pair with request ID**.

### 10.4 No arguments in request
`ApprovalRequest` contains `callId`, not arguments. `displayReason` for locale strings.
**QOC relevance:** QOC's `ToolOptions.approvalMessage`. Learning: **do not duplicate arguments**.

### 10.5 Scoped dispatch
`approval/request` is a waterfall with scope filtering (agent-scoped listeners).
**QOC relevance:** QOC. Learning: **scoped answerer dispatch**.

---

## 11. Sandbox (code-level)

### 11.1 Modes
`SandboxMode = 'read-only' | 'workspace-write' | 'danger-full-access'`. Only the first two are passed to the provider.
**QOC relevance:** QOC has no sandbox. Learning: **sandbox modes**.

### 11.2 Enforcement (full/partial)
`SandboxEnforcement = 'full' | 'partial'`.
**QOC relevance:** QOC. Learning: **enforcement as a reported fact**.

### 11.3 Per-call policy
Policy resolved per call, not provider-global. `SandboxPolicyRequest { session?, mode? }`. `SandboxExecutionPolicy { mode, workspaceRoot, additionalWritableRoots?, sessionId? }`.
**QOC relevance:** QOC. Learning: **per-call policy resolution**.

### 11.4 Runner failure rules
`RunnerFailureRule { allowedExitCodes?, fatalSignatures[], informationalLines[] }`. Case-insensitive. Exit status alone proves nothing.
**QOC relevance:** QOC. Learning: **structured runner-failure classification**.

### 11.5 Escalation
After a denied call the model can request a strictly broader mode for human approval.
**QOC relevance:** QOC. Learning: **escalation flow**.

---

## 12. Skills (code-level)

### 12.1 Ranked discovery
Roots in rank order (project-dsh 100, project-agents 200, custom 300, user-dsh 400, user-agents 500, bundled 600). Duplicate names: nearest layer wins; within a layer: rank, provider, local order.
**QOC relevance:** QOC has BM25 skill loading. Learning: **rank-based discovery** + layer precedence.

### 12.2 Skill identity
Kebab-case `^[a-z0-9]+(?:-[a-z0-9]+)*$`. Directory bundles (`<name>/SKILL.md`) + flat files (`<name>.md`). No recursive `**/SKILL.md`.
**QOC relevance:** QOC. Learning: **skill naming convention**.

### 12.3 Invocation policy
`SkillInvocationPolicy { modelInvocable, userInvocable }` as positive booleans.
**QOC relevance:** QOC has per-agent allow/deny. Learning: **explicit invocation policy**.

### 12.4 Incomplete discovery
Rejected `list()` calls are logged and excluded from observation; incomplete observation yields usable candidates without cache. `SkillProviderObservation { candidates, complete }`.
**QOC relevance:** QOC. Learning: **graceful degradation**.

### 12.5 Chokidar watcher
Existing roots are watched. Missing roots are tracked segment-wise. Model-facing `write`/`edit` invalidate synchronously.
**QOC relevance:** QOC. Learning: **file watcher for skill roots**.

### 12.6 Provider registry (host + per-scope layered)
Registration files into the layer of the calling context's scope. Discovery caches keyed by scope chain.
**QOC relevance:** QOC. Learning: **scoped skill registry**.

---

## 13. Attachments (code-level)

### 13.1 Content-addressed
`AttachmentId` = `sha256:<digest>` (branded). Consumers do not parse.
**QOC relevance:** QOC has `ModelContent.Media`. Learning: **content-addressed IDs**.

### 13.2 Persist-before-event
The object is persisted **before** the event. Session events contain only the reference + metadata.
**QOC relevance:** QOC. Learning: **persist-before-event**.

### 13.3 ImageRequestTarget & RequestImageAttachment
Deterministic request-image target per model route. `RequestImageAttachment` as cached request version (variantId, attachment, data, mediaType, bytes, width, height, depth, space, hasAlpha).
**QOC relevance:** QOC. Learning: **image-request caching** (variant-based).

### 13.4 Admission parts
`AttachmentAdmissionPart` / `AdmittedPromptContentPart` — host-admitted content with durable refs.
**QOC relevance:** QOC. Learning: **admission pipeline** for binaries.

---

## 14. Deliverables (code-level)

### 14.1 `present` tool
Model declares files as a deliverable (log-only event). `PresentedFile { path, description? }`.
**QOC relevance:** QOC has no deliverables. Learning: **deliverables as first-class**.

### 14.2 Workspace changes
Git snapshot at turn start/end + whole-file captures around file-tool edits. `WorkspaceChangedFile { path, display, added, deleted, binary? }`.
**QOC relevance:** QOC has `RevertStaged`/`RevertCommitted`. Learning: **turn diff as a deliverable**.

### 14.3 Move semantics
Move contributes deleted source + added destination. Stopped commit contributes only net changes.
**QOC relevance:** QOC. Learning: **move accounting**.

---

## 15. Background jobs (code-level)

### 15.1 Job lifecycle records
`job/registered` + `job/settled` events in the owner session. `JobId = <kind>-<short-uuid>` (16 hex). `JobStatus = running | stopping | completed | killed | failed`.
**QOC relevance:** QOC has `ExecutionStarted/Succeeded/Failed`. Learning: **job events in the session log**.

### 15.2 producerReported
`producerReported` = producer fulfilled `done` with a terminal outcome. False for forced teardown/rejection.
**QOC relevance:** QOC. Learning: **teardown vs. result separation**.

### 15.3 Missing settlement stays incomplete
Without `job/settled` in the log = incomplete, even if the registry reports termination.
**QOC relevance:** QOC. Learning: **log is truth**.

### 15.4 Ownership (scoped to agent session)
Other agents cannot inspect/stop.
**QOC relevance:** QOC. Learning: **job ownership**.

---

## 16. Schedule (code-level)

### 16.1 7 timing kinds
`after`, `at`, `every`, `daily`, `weekly`, `cron`, `rrule`.
**QOC relevance:** QOC has no scheduling. Learning: **scheduling as a capability**.

### 16.2 Storage versioning
`compatibleVersions` for old stamps. Normal edits persist current rows. Reads leave storage unchanged.
**QOC relevance:** QOC. Learning: **storage-versioning pattern**.

### 16.3 Latest-only catch-up
Missed occurrences: only the last one is delivered once, then the first future one.
**QOC relevance:** QOC. Learning: **catch-up semantics**.

### 16.4 Canonical UTC instants
`scheduledAt` = RFC 3339 UTC, ms precision, year 0001–9999. Decoding does not recalculate.
**QOC relevance:** QOC. Learning: **canonical time storage**.

---

## 17. Goals (code-level)

### 17.1 Revision-based (CAS)
`GoalRef = { id, revision }`; every durable mutation increments the revision.
**QOC relevance:** QOC. Learning: **compare-and-set** for shared state.

### 17.2 Phases
`GoalPhase = active | paused | blocked | complete`. `blocked` with `GoalBlockReason { code, message }`.
**QOC relevance:** QOC. Learning: **goal lifecycle**.

### 17.3 Activation (process-local)
`GoalActivation = armed | disarmed`. Never persisted.
**QOC relevance:** QOC. Learning: **process-local vs. durable state separation**.

### 17.4 Round cap
`maxGoalRounds` (default 256) bounds automatic continuation.
**QOC relevance:** QOC. Learning: **round cap** for agent loops.

---

## 18. Plan mode (code-level)

### 18.1 Soft guidance
Plan mode is soft guidance (not sandbox/approval). Its own event `plan/mode`, log-only, whole-value replace.
**QOC relevance:** QOC. Learning: **plan mode as a capability**.

### 18.2 Pending selections
User selection stays pending until the next accepted in-turn pre-step. `PlanProjection { active, pending }`. `PlanUnitState { active, wanted, running, activeAtLastHeader, entered }`.
**QOC relevance:** QOC. Learning: **pending-selection pattern**.

### 18.3 exit_plan_mode tool
Progressively disclosed; requires a complete markdown plan with a `#` heading; presented for review (user-questions seam).
**QOC relevance:** QOC. Learning: **plan review flow**.

### 18.4 `/plan` command
`/plan [off|message]`: bare → select plan mode; with message → select + steer; `off` → inactive + cancel pending.
**QOC relevance:** QOC. Learning: **command-based mode toggle**.

---

## 19. Todo (code-level)

### 19.1 Whole-list replacement
`todo/write` replaces the whole list (last-wins), no id/priority/activeForm. `TodoItem { content, status: pending|in_progress|completed }`.
**QOC relevance:** QOC has `TodoTool`. Learning: **minimal design**.

### 19.2 Excluded from summary
`todos: SessionProjectionSummaryExcluded<TodoItem[]> | null` — not in the session summary.
**QOC relevance:** QOC. Learning: **summary exclusion** for large projections.

---

## 20. Working directory (code-level)

### 20.1 Immutable header + change events
Original project stays the anchor. `working-directory/change { cwd }`.
**QOC relevance:** QOC. Learning: **sandbox roots must not travel with cwd**.

### 20.2 Serialize queue per session
`serialize(agent, signal, action)` — per-session promise chain for ordered mutations.
**QOC relevance:** QOC. Learning: **per-session serialization** for directory mutations.

### 20.3 ensure() restores original
If cwd disappears, restore the original project directory.
**QOC relevance:** QOC. Learning: **recovery pattern**.

### 20.4 Notice via inject()
Commit queued notice via `agent.inject()` (user/message). Failure is warned, but committed state remains.
**QOC relevance:** QOC. Learning: **best-effort notice** after commit.

### 20.5 Required user context
Working directory as a `required: true` user-context snapshot, even when optional runtime context is disabled.
**QOC relevance:** QOC. Learning: **required context** (not optional).

---

## 21. Workspace (code-level)

### 21.1 Canonical paths
`realpathNormalize` (resolve symlinks, `..`, trailing slashes) as the single uniqueness canon.
**QOC relevance:** QOC. Learning: **canonical path normalization**.

### 21.2 Session membership validation
Workspace validates session cwd against the workspace path. Missing header preserves the candidate.
**QOC relevance:** QOC. Learning: **membership validation**.

### 21.3 Status (ok/missing-dir)
Live directory check, uncached. Missing directory does not mutate the record.
**QOC relevance:** QOC. Learning: **non-mutating status check**.

### 21.4 Activity families
`SessionActivityKindMap` merge-extensible (turn, job, subagent, schedule).
**QOC relevance:** QOC. Learning: **activity-family registry**.

---

## 22. Credentials (code-level)

### 22.1 References, not values
Settings contain only references (env-var names); the provider owns the values. `CredentialRef` branded.
**QOC relevance:** QOC has provider auth. Learning: **credential references**.

### 22.2 Per-operation resolve
Resolve per operation, never cached → hot update.
**QOC relevance:** QOC. Learning: **per-operation resolve**.

### 22.3 Empty = absent
Empty value = absent.
**QOC relevance:** QOC. Learning: **empty-is-absent**.

### 22.4 CredentialInfo
`{ configured, source?, writable }` — safe view for UI, never the value.
**QOC relevance:** QOC. Learning: **safe credential view**.

### 22.5 CredentialKey (scope/id)
`<scope>/<id>` for stored records. Scope = owner plugin.
**QOC relevance:** QOC. Learning: **scoped credential keys**.

### 22.6 Resolution events
`credentials/reference-updated` + `credentials/record-updated` as emit events.
**QOC relevance:** QOC. Learning: **credential-change events**.

---

## 23. Settings (code-level)

### 23.1 Form projection
Config fields to forms. `SettingsNamespaceView { autoGenerate, ns, schema, value, base?, user?, applies, secrets, revision }`.
**QOC relevance:** QOC has settings. Learning: **form projection**.

### 23.2 Optimistic concurrency
`revision` monotonic; write with `expectedRevision`; stale refused.
**QOC relevance:** QOC. Learning: **optimistic concurrency**.

### 23.3 Path ops
`SettingsPathOpView = { op: 'set', path, value } | { op: 'unset', path }`. Empty path = root.
**QOC relevance:** QOC. Learning: **path-addressed edits**.

### 23.4 Secret redaction
`SettingsSecretView { path, set }` — value never on the wire.
**QOC relevance:** QOC. Learning: **secret redaction on the wire**.

---

## 24. Storage (code-level)

### 24.1 Hub/backend/domain form
`ctx.storage` is the meeting point. Backends (json/sqlite) own media; domain form owns semantics. Hub does no IO. `register(name, backend)` returns a disposer. `mount(form, facility)` is an effect.
**QOC relevance:** QOC has `SessionStore`/`Journal`/`ToolOutputStore`. Learning: **storage-hub pattern**.

### 24.2 Backend contract
`StorageBackend { kv?, … }`. Facets optional; missing = resolution fails loudly. `close()` idempotent.
**QOC relevance:** QOC. Learning: **facet-based backends**.

### 24.3 Domain versioning
Shared domain-version policy for JSON + SQLite.
**QOC relevance:** QOC. Learning: **versioned storage domains**.

### 24.4 storageBackendServiceKey
Lifecycle-only service key for backend registration.
**QOC relevance:** QOC. Learning: **backend lifecycle sync**.

---

## 25. MCP (code-level)

### 25.1 Scope-based
MCP servers opt in per scope. No server → no tools/prompt text.
**QOC relevance:** QOC has MCP client/server. Learning: **scope-based server visibility**.

### 25.2 Server instructions in system prompt
Server instructions join the logged system prompt (with size limit `maxInstructionBytes`).
**QOC relevance:** QOC. Learning: **MCP instructions as a prompt section**.

### 25.3 Resources as tools
Resources are discovered/read as normal tools with permission + recorded results. Binary payloads as descriptions.
**QOC relevance:** QOC. Learning: **MCP resources as first-class tools**.

### 25.4 Tool naming
`mcp__<server>__<tool>`.
**QOC relevance:** QOC. Learning: **namespacing convention**.

### 25.5 Protocol negotiation
SDK's supported revisions; no product setting for revision.
**QOC relevance:** QOC. Learning: **SDK-managed protocol negotiation**.

---

## 26. System prompt (code-level)

### 26.1 Sections with order
Prompt sections with order (e.g. `plan:policy` at 50). `system-prompt/assemble` waterfall. `context()` with `name`, `order`, `required`, `interpolate`, `text`.
**QOC relevance:** QOC has `ContextBuilder`. Learning: **ordered sections + waterfall assembly**.

### 26.2 Capability-independent tool changes
Tool changes are capability-independent; session tool history provides provider declarations.
**QOC relevance:** QOC. Learning: **tool history as a separate concern**.

### 26.3 Required user context
`required: true` user-context snapshot, even when optional runtime context is disabled.
**QOC relevance:** QOC. Learning: **required context**.

---

## 27. LLM (code-level)

### 27.1 Streams always end with a terminal result
Every stream ends with success/failure/cancellation.
**QOC relevance:** QOC has `ModelEvent.Finished`/`ProviderFailed`. Learning: **terminal-result guarantee**.

### 27.2 Loop-built requests deep-frozen
Extensions/adapters cannot rewrite requests.
**QOC relevance:** QOC. Learning: **request immutability**.

### 27.3 Provider-specific translation in adapter
Provider-specific translation stays in the adapter.
**QOC relevance:** QOC. Learning: **adapter isolation** (QOC already has this).

### 27.4 Model discovery
Model metadata + call-defaults resolution.
**QOC relevance:** QOC has `ModelInfo`/`StaticCatalogSource`. Learning: **dynamic model discovery**.

---

## 28. Guard (code-level)

### 28.1 Repeat-tool reminder
Detects repeated identical tool calls, reminds the model.
**QOC relevance:** QOC has no loop guards. Learning: **repeat detection**.

### 28.2 Timeout policy
Time limit for tool calls with a declared timeout.
**QOC relevance:** QOC. Learning: **tool-timeout policy**.

---

## 29. Identity & telemetry (code-level)

### 29.1 Anonymous user ID
One anonymous ID per harness home for telemetry/feedback/provider requests.
**QOC relevance:** QOC. Learning: **installation identity** for telemetry.

### 29.2 OTEL transport plugins
Shared telemetry transport; business consumers choose events/redaction.
**QOC relevance:** QOC has `LlmTrace`. Learning: **OTEL integration**.

---

## 30. Extensions (code-level)

### 30.1 Read-only API discovery
Runtime API inspection for agents.
**QOC relevance:** QOC. Learning: **API discovery** for agents.

### 30.2 Process-local runners
Programmatic + browser consumers.
**QOC relevance:** QOC. Learning: **process-local execution**.

---

## 31. Preset (code-level)

### 31.1 Agent capabilities via config
Agent capabilities through ordinary Cordis config. Revision management.
**QOC relevance:** QOC has `AgentProfile`/`AgentSelection`. Learning: **config-based agent composition**.

---

## 32. PTC runtime (code-level)

### 32.1 Fresh process per async function
Node evaluates async function bodies in fresh processes. REPL retains one process per state owner. Script retention optional.
**QOC relevance:** QOC has no PTC. Learning: **PTC execution model**.

### 32.2 Failures as results
Failures return as results, not exceptions.
**QOC relevance:** QOC. Learning: **failure-as-result**.

---

## 33. Document (code-level)

### 33.1 Office→PDF conversion
LibreOffice kit. Targets with a native engine use it; others use Node WASM.
**QOC relevance:** QOC. Learning: **document conversion** as a capability.

---

## 34. SSH (code-level)

### 34.1 Remote providers via shared connection
Filesystem/subprocess/sandbox over SSH. Provider-owned paths.
**QOC relevance:** QOC has no SSH execution. Learning: **remote execution providers**.

---

## 35. Terminal (code-level)

### 35.1 Persistent PTY
Interactive shells/REPLs across tool calls. Owner-isolated. Not restart-surviving.
**QOC relevance:** QOC has no persistent terminals. Learning: **persistent terminal** as a capability.

---

## 36. LSP (code-level)

### 36.1 Code navigation
Go-to-definition, find-references, hover. Provider choice transparent.
**QOC relevance:** QOC. Learning: **LSP integration**.

---

## 37. Web (code-level)

### 37.1 Search/fetch providers
Exa/Perplexity/DeepSeek for search, anonymous HTTP(S)/DeepSeek for fetch.
**QOC relevance:** QOC. Learning: **web access as a capability** (QOC has Firecrawl MCP).

---

## 38. Browser-use / computer-use (code-level)

### 38.1 Exclusive provider registration
Only one provider per composition.
**QOC relevance:** QOC. Learning: **exclusive provider pattern**.

---

## 39. Context (code-level)

### 39.1 Request-context plugins
Agent instructions, @file mentions, session references, time, tmux location. Durable (enter session history as user-role messages).
**QOC relevance:** QOC has `ContextBuilder`. Learning: **context as plugins** with durable injection.

---

## 40. FS (code-level)

### 40.1 Write intents
`createIfAbsent`, `replaceIfVersion`. Omitting means unconditional create-or-overwrite.
**QOC relevance:** QOC. Learning: **guarded writes**.

### 40.2 Move outcome
`FsMoveOutcome = { sourceBefore, destination, sourceDeleted: true } | { …, sourceDeleted: false, sourceFailure }`. Destination published even if source removal fails.
**QOC relevance:** QOC. Learning: **move with partial-failure reporting**.

### 40.3 Patch hunks
`FS_PATCH_MISMATCH` for patch failures.
**QOC relevance:** QOC. Learning: **patch-hunk support**.

### 40.4 Path-level symlink probe
`FsPathInfo` with `type: 'symlink'` for trust-boundary checks before resolve.
**QOC relevance:** QOC. Learning: **symlink detection before resolve**.

---

## 41. Concrete adoption recommendations for QOC (prioritized by impact)

Rating scale: **Impact** 1–5 (product value), **Effort** 1–5 (dev time), **Integrability** (no architecture break: high/medium/low). Architecture break = change to `engine.core` boundaries, port principle, package visibility, or ArchUnit rules.

### 41.1 Prioritization matrix (P0/P1/P2)

| # | Recommendation | Impact | Effort | Integr. | Category |
|---|---|---|---|---|---|
| 1 | Empty-session purge (>2h) | 3 | 1 | high | ✅ done |
| 2 | Fail-closed approval (missing answerer → `unavailable`) | 4 | 1 | high | P0 |
| 3 | Symlink-safe writes (O_EXCL, 0600) | 4 | 1 | high | P0 |
| 4 | Canonical path normalization (realpath) | 3 | 1 | high | P0 |
| 5 | Branded types for seq/offset/version tokens | 3 | 2 | high | P0 |
| 6 | Typed FS error codes (like FailureKind) | 3 | 2 | high | P0 |
| 7 | Capability flags for subagents | 3 | 1 | high | P0 |
| 8 | Depth limit for subagents | 4 | 1 | high | P0 |
| 9 | Empty-is-absent (credentials/settings) | 3 | 1 | high | P0 |
| 10 | Repeat-tool reminder + timeout policy (guards) | 4 | 3 | high | P0 |
| 11 | Per-session approval policy (`ask`/`never`) as event | 4 | 3 | medium | P0 |
| 12 | Audit pair (`approval/asked`+`decided`) with request ID | 4 | 3 | medium | P0 |
| 13 | Credential references + per-operation resolve | 5 | 4 | medium | P1 |
| 14 | FsVersion stale guards + createIfAbsent | 4 | 3 | medium | P1 |
| 15 | FsWriteOutcome before/after for diff/revert | 4 | 2 | high | P1 |
| 16 | Fs move with partial-failure reporting | 3 | 2 | high | P1 |
| 17 | Pinned session titles + model identity + bounded aggregate | 3 | 2 | high | P1 |
| 18 | Optimistic concurrency (revision) for settings | 3 | 3 | medium | P1 |
| 19 | Path-addressed settings edits (set/unset) | 3 | 3 | medium | P1 |
| 20 | Secret redaction on the wire | 4 | 2 | medium | P1 |
| 21 | Opaque spill locators + best-effort policy | 4 | 3 | medium | P1 |
| 22 | Tool-result pruning before compaction | 4 | 3 | medium | P1 |
| 23 | Compaction lock events (crash safety) | 4 | 3 | medium | P1 |
| 24 | Surface/shadowed/log-only classification | 4 | 4 | medium | P1 |
| 25 | System-prompt sections with order + waterfall assembly | 3 | 2 | high | P1 |
| 26 | Per-session serialization (directory mutations) | 3 | 2 | high | P1 |
| 27 | Capability flags for subagents (extended) | 3 | 2 | high | P1 |
| 28 | Empty-is-absent for provider config | 3 | 1 | high | P1 |
| 29 | Canonical path normalization (extended) | 3 | 2 | high | P1 |
| 30 | Formalize capability seams | 5 | 5 | low | P2 |
| 31 | Sandbox modes + enforcement | 4 | 5 | low | P2 |
| 32 | PTC (programmatic tool calling) | 3 | 5 | low | P2 |
| 33 | Deliverables (present + workspace changes) | 3 | 4 | medium | P2 |
| 34 | Background jobs (session lifecycle records) | 4 | 4 | medium | P2 |
| 35 | Scheduling (7 timing kinds) | 3 | 5 | low | P2 |
| 36 | Goals (revision-based) | 3 | 4 | medium | P2 |
| 37 | Plan mode (soft guidance) | 3 | 4 | medium | P2 |
| 38 | Session projections (detached readers) | 4 | 4 | medium | P2 |
| 39 | Session query (cross-corpus, surface classification) | 3 | 4 | medium | P2 |
| 40 | Working directory as event + sandbox-root anchor | 4 | 3 | medium | P2 |
| 41 | Workspace (canonical paths, membership, activity) | 3 | 4 | medium | P2 |
| 42 | MCP scope-based + instructions in system prompt | 3 | 3 | medium | P2 |
| 43 | Adjacent migrations for event-log format | 4 | 5 | low | P2 |
| 44 | Image-request caching (variant-based) | 3 | 3 | medium | P2 |
| 45 | Image offload events | 3 | 3 | medium | P2 |
| 46 | Terminal (persistent PTY) | 2 | 4 | low | P2 |
| 47 | LSP integration | 2 | 4 | low | P2 |
| 48 | SSH remote execution providers | 2 | 5 | low | P2 |
| 49 | Web-access capability | 2 | 3 | medium | P2 |
| 50 | Document conversion (office→PDF) | 2 | 3 | medium | P2 |
| 51 | Browser/computer-use (exclusive provider) | 2 | 5 | low | P2 |
| 52 | Context as plugins (durable injection) | 3 | 4 | medium | P2 |
| 53 | Anonymous installation identity | 2 | 2 | high | P2 |
| 54 | OTEL integration | 3 | 3 | medium | P2 |

---

### 41.2 P0 — detailed descriptions

#### 2. Fail-closed approval (Impact 4, Effort 1, Integr. high)
**What:** When no answerer is available or throws, the approval is treated as `unavailable` (not opened).
**Why:** Prevents a missing UI channel from silently becoming `allowed`.
**Integration:** Adjust only `WebApprovalSource`/`DurableApprovalSource`: on a missing answerer return `ToolOutcome`/`StepResult` as denied. No architecture break — purely adapter layer.
**Effort:** 1 day (code + tests).

#### 3. Symlink-safe writes (Impact 4, Effort 1, Integr. high)
**What:** Filesystem writes with `O_EXCL` (create) and mode 0600 to prevent symlink attacks.
**Why:** Security for agent-driven filesystem operations.
**Integration:** Adjust `JdkFileSystem`/`PosixFileSystem`: `Files.createFile` with `CREATE_NEW` + `posix:permissions`. No port break.
**Effort:** 1 day.

#### 4. Canonical path normalization (Impact 3, Effort 1, Integr. high)
**What:** `realpath` normalization (resolve symlinks, `..`, trailing slashes) as the single uniqueness canon.
**Why:** Prevents path confusion and duplicate workspace entries.
**Integration:** In the filesystem adapter and `WorkspaceStore` (if present). QOC has no workspace concept, but filesystem tools benefit.
**Effort:** 1 day.

#### 5. Branded types for seq/offset/version tokens (Impact 3, Effort 2, Integr. high)
**What:** Introduce `SessionSeq`, `SessionLogOffset`, `FsVersion`, `FsTargetKey` as branded types (Java: records with validation or value objects).
**Why:** Prevents confusion of seq vs. offset vs. instant at compile time.
**Integration:** New types only in `engine.core`, no existing signatures changed (additive).
**Effort:** 2–3 days (types + migration of callers).

#### 6. Typed FS error codes (Impact 3, Effort 2, Integr. high)
**What:** `FsErrorCode` enum (`FS_NOT_FOUND`, `FS_STALE_VERSION`, `FS_AMBIGUOUS_EDIT`, …) analogous to `FailureKind`.
**Why:** Structured error handling in tools/UI.
**Integration:** New enum in `engine.core`, `FsError` exception. Tools throw typed codes.
**Effort:** 2 days.

#### 7. Capability flags for subagents (Impact 3, Effort 1, Integr. high)
**What:** `SubagentCapabilities { agentOptions, agentPreset, outputSchema, depthLimit }` as flags on the provider.
**Why:** Prevents a provider from accepting options it cannot handle.
**Integration:** In the `A2AClient`/`McpClient` adapter as capability flags. No port break.
**Effort:** 1 day.

#### 8. Depth limit for subagents (Impact 4, Effort 1, Integr. high)
**What:** `maxDepth` cap for recursive subagent calls.
**Why:** Prevents infinite recursion (cost/stability risk).
**Integration:** In `agent_create`/A2A client as parameter + check.
**Effort:** 1 day.

#### 9. Empty-is-absent (Impact 3, Effort 1, Integr. high)
**What:** Empty string/null = absent (provider URL, API key, config values).
**Why:** Prevents empty config values from being treated as valid values.
**Integration:** Validation in `ProviderProducer`/config loading.
**Effort:** 1 day.

#### 10. Repeat-tool reminder + timeout policy (Impact 4, Effort 3, Integr. high)
**What:** Guard that detects repeated identical tool calls and injects a reminder to the model; timeout policy for tools with a declared timeout.
**Why:** Prevents stuck loops and hanging calls.
**Integration:** As a `ToolHook`/`Step` phase (pre-execute). QOC has `ToolHooks` — implement it there. No architecture break.
**Effort:** 3–4 days.

#### 11. Per-session approval policy `ask`/`never` (Impact 4, Effort 3, Integr. medium)
**What:** `ApprovalPolicy = 'ask' | 'never'` as a per-session event. `never` deterministically returns `rejected`. Effective value = latest `approval/policy` event, fallback service config.
**Why:** Headless/CI operation without prompts; replayable.
**Integration:** New session event `approval/policy` + `SessionStore`/`Journal`. Medium because of event schema + replay.
**Effort:** 3–4 days.

#### 12. Audit pair with request ID (Impact 4, Effort 3, Integr. medium)
**What:** `approval/asked` + `approval/decided` as a log-only pair, correlated via `ApprovalRequestId`.
**Why:** Auditability of approvals.
**Integration:** New events + store. Medium.
**Effort:** 3 days.

---

### 41.3 P1 — detailed descriptions

#### 13. Credential references + per-operation resolve (Impact 5, Effort 4, Integr. medium)
**What:** Settings contain only references (env-var names); the provider owns the values. Resolve per operation (no cache) → hot rotation.
**Why:** Highest impact: secrets out of config, rotation without restart.
**Integration:** Adjust `ProviderProducer` and config loading: instead of an `apiKey` string, resolve a `CredentialRef`. Medium-high, because config schema and UI are affected. No port break (adapter layer).
**Effort:** 1 week.

#### 14. FsVersion stale guards (Impact 4, Effort 3, Integr. medium)
**What:** `replaceIfVersion(version)` / `createIfAbsent` intents in the filesystem.
**Why:** Prevents lost updates on concurrent edits.
**Integration:** Extend the `FileSystem` port (new methods/parameters). Port change, but additive.
**Effort:** 3–4 days.

#### 15. FsWriteOutcome before/after (Impact 4, Effort 2, Integr. high)
**What:** Write/edit/delete results carry `before`/`after` (LF-normalized).
**Why:** UI diff and revert without re-reading.
**Integration:** Extend return types in the `FileSystem` port. Additive.
**Effort:** 2 days.

#### 16. Fs move partial failure (Impact 3, Effort 2, Integr. high)
**What:** Move publishes the destination even when source removal fails; `sourceDeleted: false + sourceFailure`.
**Why:** Correct error reporting on partial failures.
**Integration:** `move` method in the port.
**Effort:** 2 days.

#### 17. Pinned session titles (Impact 3, Effort 2, Integr. high)
**What:** User rename pins the title (stops auto). Model-identity tracking. `TitleInputState` as a bounded aggregate `{ first, count, lastSeq }`.
**Why:** Consistent title behavior; no overwrite by auto-title after a user rename.
**Integration:** Adjust `TitleGenerator` + `SessionStore`.
**Effort:** 2 days.

#### 18. Optimistic concurrency for settings (Impact 3, Effort 3, Integr. medium)
**What:** `revision` monotonic; write with `expectedRevision`; stale refused.
**Why:** Prevents lost updates on concurrent settings edits.
**Integration:** `SettingsStore` + UI.
**Effort:** 3 days.

#### 19. Path-addressed settings edits (Impact 3, Effort 3, Integr. medium)
**What:** `set(path, value)` / `unset(path)` instead of whole-replace.
**Why:** Granular edits, secret preservation.
**Integration:** Extend the settings API.
**Effort:** 3 days.

#### 20. Secret redaction on the wire (Impact 4, Effort 2, Integr. medium)
**What:** Secrets never on the wire; `SettingsSecretView { path, set }`.
**Why:** Security.
**Integration:** Adjust UI/REST API.
**Effort:** 2 days.

#### 21. Opaque spill locators (Impact 4, Effort 3, Integr. medium)
**What:** `SpillLocator` branded/opaque; consumers render with `retrievalHint`, do not parse.
**Why:** Decoupling from the storage backend.
**Integration:** Adjust QOC's `ToolResultCache`/artifact ref.
**Effort:** 3 days.

#### 22. Tool-result pruning (Impact 4, Effort 3, Integr. medium)
**What:** Replace large tool results with head/tail + spill locator (best-effort).
**Why:** Context savings.
**Integration:** In `CompactionService` or `Step` (pre-request).
**Effort:** 3 days.

#### 23. Compaction lock events (Impact 4, Effort 3, Integr. medium)
**What:** `compaction/start` (lock) → summary → `compaction/end` (release). Crash = detectable orphaned lock.
**Why:** Crash safety for compaction.
**Integration:** `CompactionService` + `Journal`.
**Effort:** 3 days.

#### 24. Surface/shadowed/log-only (Impact 4, Effort 4, Integr. medium)
**What:** Event classification `current | shadowed | log-only`. `foldSurface()` computes the model-visible view.
**Why:** Correct replay after compaction.
**Integration:** Extend `ConversationFolding` + `Journal`.
**Effort:** 4 days.

#### 25. System-prompt sections with order (Impact 3, Effort 2, Integr. high)
**What:** Prompt sections with order (e.g. `plan:policy` at 50). Waterfall assembly.
**Why:** More flexible prompt composition.
**Integration:** Adjust `ContextBuilder`.
**Effort:** 2 days.

#### 26. Per-session serialization (Impact 3, Effort 2, Integr. high)
**What:** Per-session promise chain for ordered mutations (e.g. directory changes).
**Why:** Prevents race conditions on concurrent mutations.
**Integration:** In `SessionService` or `ToolRuntime`.
**Effort:** 2 days.

#### 27. Capability flags (extended) (Impact 3, Effort 2, Integr. high)
**What:** Capability flags on the provider (agentOptions, agentPreset, outputSchema, depthLimit).
**Why:** Provider capabilities made explicit.
**Integration:** In `ProviderAdapter`/`ModelInfo`.
**Effort:** 2 days.

#### 28. Empty-is-absent (extended) (Impact 3, Effort 1, Integr. high)
**What:** Empty values = absent (provider URL, API key, config).
**Why:** Consistency.
**Integration:** Config loading + `ProviderProducer`.
**Effort:** 1 day.

#### 29. Canonical path normalization (extended) (Impact 3, Effort 2, Integr. high)
**What:** realpath normalization (symlinks, `..`, trailing slashes).
**Why:** Prevents path confusion.
**Integration:** Filesystem adapter + workspace (if present).
**Effort:** 2 days.

---

### 41.4 P2 — short reference only (high effort / low integration maturity)

Sandbox modes, PTC, deliverables, background jobs, scheduling, goals, plan mode, session projections, session query, working directory as event, workspace, MCP scope, adjacent migrations, image caching, image offload, terminal, LSP, SSH, web access, document conversion, browser/computer-use, context-as-plugins, anonymous identity, OTEL, formalizing capability seams.

These require either new subsystems or deeper architecture changes and only make sense once P0/P1 are complete.

---

### 41.5 Do not adopt (QOC solves these differently)
- Plugin architecture (Cordis) — QOC is Java/Quarkus with a fixed core; ports suffice.
- TypeScript/Node ecosystem — different runtime.
- Web UI details — QOC has its own UI.
- Session format v4 details — QOC uses a DB, not JSONL.
- Capability seams as a plugin system — QOC uses the port principle.

## 42. References

- `deepseek-harness/docs/architecture.md`
- `deepseek-harness/docs/agent-lifecycle.md`
- `deepseek-harness/docs/tool-execution-pipeline.md`
- `deepseek-harness/docs/session-format-status.md`
- `deepseek-harness/docs/subsystems/*.md`
- `deepseek-harness/packages/*/README.md`
- `deepseek-harness/packages/*/src/*.ts` (types and implementations)