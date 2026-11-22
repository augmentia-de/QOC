# Implementation Plan: Remediation of Identified QOC Weaknesses (P0–P2)

> **Goal:** Fix the QOC weaknesses identified in comparison with OpenCode (security,
> semantics, performance, product maturity) in prioritized order.
>
> **References:**
> - `qoc/docs/QOC-vs-OpenCode-Extensions.md`
> - `qoc/docs/Features.md`
> - Code under `qoc/engine/`, `qoc/workflow-runner/`, `qoc/quarkus/`, `qoc/reactflow/`
>
> **Status:** Implemented (2026-11-22) — P0 (P0-1..P0-4) and P1 (P1-1..P1-10) fully implemented. P2 items (P2-1 runtime adaptation spec-only, P2-2 Temporal out of scope, P2-3 MCP OAuth stubs, P2-4 A2A hardening, P2-5 Agent Store cleanup) are explicitly deferred/out of scope.
> **Priorities:** P0 = immediate, P1 = next iteration, P2 = after stabilization

---

## Implementation Status (2026-11-18)

Already implemented:
- P0-1 Ownership for workflow runs (`WorkflowResource` list/status/resume).
- P0-2 Stable HITL approval IDs (no more `System.identityHashCode`).
- P0-3 Nested subworkflow execution (`DefaultNodeDispatcher`) with depth limit.
- P0-4 Fork/Join documented and validated as structural nodes.
- P1-1 Journal `lastSeq(...)` instead of full-history scans in hot paths.
- P1-2 `SessionStore.listByTitlePrefix(...)` for run-scan optimization.
- P1-4 MCP server remains tool-only; OAuth stubs are documented, not implemented.
- P1-10 Editor palette extended with `guard` and `inject` (16 node types).
- P1-3 Fork/Join form validation in `GraphCompiler`.
- P1-5 Opt-in strict structured output (`qoc.structured-output.strict` / `QOC_STRUCTURED_OUTPUT_STRICT`).
- P1-7 Tool outputs on filesystem (`FileSystemToolOutputStore`) instead of DB blobs for SQL/Postgres.
- P1-9 Resilience metrics in `/api/metrics` (`resilienceMetrics()`).

Still open: P2-1 through P2-5 (explicitly deferred/out of scope). P1-9 metrics implemented.

---

## 0. Guiding Principles

1. **No silent semantic changes.** If `subworkflow`, `fork`, or `guard`
   currently work only structurally/expansively, either actually execute them
   or have the UI/docs say so explicitly.
2. **Security before convenience.** Workflow runs are sessions; ownership checks are
   mandatory for run list, status, and resume.
3. **Durable IDs instead of process identity.** IDs for HITL and replay must be
   derived from session/run/node, not from `System.identityHashCode`.
4. **Performance as a feature.** Journal scans, run scans, and DB blobs are treated
   as technical debt, not details.
5. **Concepts remain concepts.** Runtime adaptation and MCP OAuth are not
   advertised as complete until code and tests exist. Temporal is not needed.

---

## 1. P0 – Security and Core Semantics

### P0-1: Close Ownership for Workflow Runs

**Problem:** `WorkflowResource.listRuns`, `runStatus`, and `resumeRun` perform no
ownership check, even though workflow runs are normal sessions.

**Goal:** Only admins or owners may list, read, or resume runs.

**Implementation:**
- `listRuns`: filter runs by `SessionInfo.ownerId()`, analogous to
  `listWorkflows()`.
- `runStatus`: `accessGuard.requireAccess(runId)` or
  `requireWorkflowAccess(ownerId)` before replay.
- `resumeRun`: check ownership before `executor().resume(runId)`; return `403`
  for foreign runs.
- Optional: expose owner from `WorkflowRunSnapshot`/`SessionInfo` in
  `WorkflowRunJournal`, so no second session query is needed.

**Files:**
- `quarkus/.../http/WorkflowResource.java`
- `quarkus/.../http/SessionAccessGuard.java`
- `workflow-runner/.../runner/WorkflowRunJournal.java`

**Tests:**
- Quarkus integration test: User A cannot read/resume User B's run.
- Admin can read all runs.
- Anonymous/empty principal behaves as before (shared mode).

**Acceptance:**
- `GET /api/workflows/runs`, `GET /api/workflows/runs/{runId}`,
  `POST /api/workflows/runs/{runId}/resume` return `403` for foreign runs.

---

### P0-2: Stable HITL Approval IDs

**Problem:** `DefaultNodeDispatcher` creates
`"hitl:" + currentNodeId + ":" + System.identityHashCode(ctx)`. The ID is
not stable across restarts and contradicts the "deterministic" comment.

**Goal:** Derive approval ID from run/session/node, not from object identity.

**Implementation:**
- Make run ID or session ID available to `NodeDispatcher`/`DefaultNodeDispatcher`
  via `WorkflowStepProviderImpl`/`WorkflowContext`.
- Approval ID as `hitl:<sessionId>:<nodeId>` or `hitl:<runId>:<nodeId>`.
- The same ID must be reconstructed on replay.
- `DurableApprovalSource.resolve(...)` stays token-based; only the internally
  referenced approval ID becomes stable.

**Files:**
- `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- `workflow-runner/.../runner/WorkflowStepProviderImpl.java`
- `workflow-runner/.../runner/WorkflowContext.java`
- `workflow-runner/.../runner/WorkflowExecutor.java`

**Tests:**
- Unit test: same run/node yields same `approvalId`.
- Resume test: approval ID identical after replay.

**Acceptance:**
- No more `System.identityHashCode(ctx)` in approval paths.

---

### P0-3: Clarify and Implement Subworkflow Semantics

**Problem:** `subworkflow` expands the child graph but does not execute it.
UI/docs suggest workflow execution.

**Option A (recommended short-term): make semantics explicit**
- Document node as "subworkflow reference/expansion".
- Do not claim execution.
- Validation checks existence of the child workflow.

**Option B (medium-term): real nested execution**
- `DefaultNodeDispatcher` calls `WorkflowExecutor.executeRun(subWf, input, principal)`.
- Child run ID and child session ID journaled.
- Errors/pauses of the child run propagate to the parent run.
- Recursion/depth limit and cycle protection.

**Files:**
- `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- `workflow-runner/.../runner/SubWorkflowExpander.java`
- `workflow-runner/.../runner/WorkflowExecutor.java`
- `reactflow/src/nodeTypes.tsx`, `reactflow/src/EditorPage.tsx`
- `docs/Features.md`, `docs/engineering/Workflow.md`

**Tests:**
- Unit: missing child workflow yields deterministic error.
- Integration: successful child run writes output into parent state.
- Pause: child run `paused` durably pauses parent.

**Acceptance:**
- Either real execution with tests or UI/docs without execution claims.
- No mixed form in public docs.

---

### P0-4: Clarify Fork/Join Semantics

**Problem:** `fork`/`join` are structural markers, not parallel execution.

**Implementation:**
- Short-term: describe in docs/UI as "structural branch/merge nodes".
- Medium-term: either real fan-out/fan-in in the step loop or remove the
  impression of parallel execution.
- `join` should deterministically merge outputs of all incoming nodes
  (already partially).

**Files:**
- `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- `docs/Features.md`, `docs/engineering/Workflow.md`, `reactflow/src/nodeTypes.tsx`

**Acceptance:**
- No wording "parallel execution" without implementation.

---

## 2. P1 – Product Maturity, Performance, and Integration

### P1-1: Journal Performance (`history().size()`)

**Problem:** `Engine.java` calls `journal.history(id, Seq.zero()).size()` multiple
times per prompt; each call loads the entire history.

**Implementation:**
- Add `Journal.lastSeq(session)` / `nextSeq(session)` or use an
  `event_sequence` table as in OC.
- Use `history()` only for replay/projection.
- Derive ConversationStore `nextSeq` from journal instead of `max(seq)`.

**Files:**
- `engine/.../core/port/store/Journal.java`
- `engine/.../adapter/sql/SqlJournal.java`
- `engine/.../adapter/sqlite/SqliteJournal.java`
- `engine/.../http/Engine.java`

**Tests:**
- Performance test/benchmark: 1000 events, no full scan on prompt.
- Regression: replay and sequence numbers remain correct.

**Acceptance:**
- No `history(...).size()` in hot path.

---

### P1-2: Run Scan and Pagination

**Problem:** `WorkflowRunJournal.scan(limit)` loads all sessions, filters by
title prefix, and replays each session. With many runs this is O(n) with high
latency.

**Implementation:**
- DB query with `title LIKE 'workflow:%'` and `ORDER BY updated DESC LIMIT ?`.
- Optional owner filter in SQL.
- Cursor/pagination instead of just `limit`.

**Files:**
- `workflow-runner/.../runner/WorkflowRunJournal.java`
- `engine/.../core/port/store/SessionReader.java`
- SQL/SQLite adapter

**Tests:**
- 1000 sessions, scan stays within acceptable time.
- Pagination yields stable ordering.

**Acceptance:**
- `scan(limit)` does not perform full-table replay for all sessions.

---

### P1-3: Sharpen Graph Validation

**Problem:** Docs claim "exactly one `end`", code requires at least one.
Fork/Join are not structurally checked.

**Implementation:**
- Decision: either enforce exactly one `end` (recommended for clear
  workflows) or align docs with code.
- Validation for `fork` (at least two outgoing edges) and `join`
  (at least two incoming edges).
- Optional `subworkflow` existence check with store context.

**Files:**
- `workflow-runner/.../validation/GraphCompiler.java`
- `docs/Features.md`
- `reactflow/src` (validation feedback)

**Tests:**
- Unit tests for 0/1/multiple `end` nodes.
- Unit tests for degenerate fork/join.

**Acceptance:**
- Docs and code agree.

---

### P1-4: Extend or Limit MCP Server

**Problem:** MCP server offers only two tools; no resources/prompts/sessions;
OAuth endpoints are stubs.

**Implementation:**
- Short-term: clearly document as "tool-only MCP server" in OpenAPI/docs.
- Medium-term:
  - Resources for workflow definitions/runs.
  - Prompts for agent templates.
  - OAuth or explicit disabling of stubs.
- Further harden registry/serverless roles.

**Files:**
- `quarkus/.../http/McpServerResource.java`
- `docs/guides/*`, `docs/security.md`

**Tests:**
- Contract tests for `initialize`, `tools/list`, `tools/call`.
- 401/403 tests in OAuth mode.

**Acceptance:**
- No `501` stubs in productive default path or clear documentation.

---

### P1-5: Harden Structured Outputs

**Problem:** `submit_result` works non-strict; deviations are detected
reactively and may trigger retries.

**Implementation:**
- Implement strict mode as opt-in flag (Path B from
  `docs/plans/strict-structured-outputs.md`).
- `additionalProperties(false)`, all fields required, `.strict(true)` only for
  OpenAI/Azure.
- Validation at startup: schema strict-compatible?
- Metrics: retry rate, schema violations.

**Files:**
- `engine/.../core/json/Schema.java`
- `quarkus/.../http/LangChain4jProviderAdapter.java`
- `docs/CONFIG.md`

**Tests:**
- Unit: strict schema transformation.
- Integration with real key (CI secret) optional.

**Acceptance:**
- Flag `qoc.structured-output.strict` demonstrably takes effect.

---

### P1-6: Extend or Honestly Limit Guardrails

**Problem:** `Features.md`/marketing speaks of declarative rules/CDI SPI,
code only has external webhook.

**Implementation:**
- Short-term: clearly document/UI as "Webhook Guardrail".
- Medium-term:
  - Local rule definitions (e.g. tool allow/deny, parameter patterns).
  - CDI SPI for in-process guardrails.
  - Audit log for block/rewrite/redact.
- Keep webhook egress security (host/IP/TLS).

**Files:**
- `quarkus/.../guardrail/GuardrailHooks.java`
- `quarkus/.../guardrail/GuardrailRegistry.java`
- `quarkus/.../guardrail/WebhookClient.java`
- `docs/webhook.md`, `docs/security.md`

**Tests:**
- Unit: block/rewrite/redact/ask.
- Security tests for disallowed hosts/IPs.

**Acceptance:**
- No claim of declarative rules without implementation.

---

### P1-7: DB Blobs and Tool Outputs to Filesystem/Object Storage

**Problem:** `tool_output` and `snapshot_file` store large content in the DB;
OC uses filesystem/Git.

**Implementation:**
- Tool outputs like OC on FS with retention and inline limit.
- Snapshots continue Git-based or object storage.
- DB stores only metadata/hash/URI.

**Files:**
- `engine/.../adapter/sql/*ToolOutput*`
- `engine/.../adapter/sql/*Snapshot*`
- `engine/.../core/port/store/ToolOutputStore.java`
- `engine/.../core/port/store/SnapshotStore.java`

**Tests:**
- Migration/compatibility test.
- Large payload test.

**Acceptance:**
- No large blobs in DB hot path.

---

### P1-8: Tool Result Cache TTL/Invalidation

**Problem:** `tool_result_cache` has no TTL/invalidation; cache can go stale.

**Implementation:**
- TTL field and cleanup job.
- Invalidation on Write/Edit/Patch/Shell side effects.
- Configurable cache policy.

**Files:**
- `engine/.../runtime/tool/runtime/ToolRuntime.java`
- `engine/.../core/port/store/ToolResultCache.java`
- SQL/SQLite adapter
- Migration

**Tests:**
- Cache hit/expiry.
- Side-effect invalidation.

**Acceptance:**
- Cache cannot serve stale results indefinitely.

---

### P1-9: Resilience Metrics and Observability

**Problem:** Preflight, triage, loop detector exist, but usefulness/health
are hard to observe.

**Implementation:**
- Metrics for preflight failures, triage attempts, loop detections,
  webhook blocks, verify-on-stop failures.
- Health/readiness considers preflight.
- SSE/OTel events for guard decisions.

**Files:**
- `engine/.../runtime/resilience/*`
- `quarkus/.../http/*`
- `docs/optimization.md`, `docs/observability` (if present)

**Tests:**
- Unit: metric increment.
- Integration: SSE event on block.

**Acceptance:**
- Operations can see guard/resilience events.

---

### P1-10: Align Editor Palette and Node Types

**Problem:** Backend supports 16 types, editor registers 14 (`guard`, `inject`
missing).

**Implementation:**
- Either implement `guard`/`inject` in editor or remove from backend/docs.
- UI validation against `GraphCompiler.VALID_NODE_TYPES`.

**Files:**
- `reactflow/src/nodeRegistry.ts`
- `reactflow/src/EditorPage.tsx`
- `reactflow/src/nodeTypes.tsx`

**Tests:**
- Frontend tests for all node types.

**Acceptance:**
- No discrepancy between backend and editor.

---

## 3. P2 – Concepts and Future Capabilities

### P2-1: Runtime Adaptation (specification only until code exists)

**Problem:** Documented but not implemented.

**Implementation:**
- Finalize spec (inputs, outputs, triggers).
- Only then implement `RequirementChecker`, `AdaptationPlan`, `RuntimePlan`, `PlanValidator`.
- Tests for plan validation.

**Files:**
- `docs/next/runtime-plan-adaptation.md`
- `docs/plans/runtime-plan-adaptation.md`
- new package `engine/.../runtime/adaptation/`

**Acceptance:**
- No code claims before implementation; spec remains marked as plan.

---

### P2-2: Temporal Integration (not needed / out of scope)

Temporal is an external, distributed workflow orchestration platform. QOC already
has journal-based recovery, resume, and checkpoints; a Temporal integration is
therefore optional and currently not required. It remains a concept/documentation
and is not counted as a feature.

---

### P2-3: MCP OAuth Server

**Problem:** Stubs return `501`.

**Implementation:**
- Either implement full OAuth flow (authorize, callback, token) or remove/disable
  endpoints and adjust docs.
- Security tests for token validation.

**Files:**
- `quarkus/.../http/McpServerResource.java`
- `quarkus/.../http/security/*`

**Acceptance:**
- No half-baked OAuth mode in default.

---

### P2-4: A2A Hardening

**Problem:** A2A server is opt-in; AuthZ/routing present, but maturity and
tests need expansion.

**Implementation:**
- Agent card discovery and task lifecycle tests.
- Standardize AuthN/AuthZ for A2A endpoints.
- Test error paths (timeout, cancel, retry).

**Files:**
- `quarkus/.../http/a2a/*`
- `engine/.../runtime/tool/a2a/*`

**Acceptance:**
- A2A can be documented as stable integration.

---

### P2-5: Agent Store Consistency and Cleanup

**Problem:** File/DB agent store with cache; generated agents can accumulate.

**Implementation:**
- Cleanup policy for `gen-...` agents.
- Cache invalidation on external changes.
- Ownership/concurrency tests.

**Files:**
- `workflow-runner/.../store/AgentStore.java`
- `workflow-runner/.../store/AgentStorePort.java`
- `quarkus/.../http/AgentStoreResource.java`

**Acceptance:**
- No stale generated agents after workflow deletion.

---

## 4. Cross-Cutting: Tests and Quality

For every P0/P1 task:
- Unit tests for core logic.
- Integration tests for HTTP/DB where Maven/Quarkus test infrastructure exists.
- ArchUnit rules for new packages (`engine.core` stays free of Quarkus/Jackson/CDI/HTTP).
- JaCoCo ≥ 80 % instructions for affected modules before merge.
- `mvn test` green.

---

## 5. Recommended Order

1. **Week 1:** P0-1, P0-2, P0-3 (Option A), P0-4 documentation.
2. **Week 2:** P1-1, P1-2, P1-3.
3. **Week 3:** P1-4, P1-5, P1-6.
4. **Week 4:** P1-7, P1-8, P1-9, P1-10.
5. **Afterwards:** P2 as needed.

---

## 6. Definition of Done

A weakness is considered fixed when:
- code and tests exist,
- docs/features are consistent,
- no overstated claims remain,
- `mvn test` is green for affected modules,
- and the change can be marked as done in
  `qoc/docs/QOC-vs-OpenCode-Extensions.md`.
