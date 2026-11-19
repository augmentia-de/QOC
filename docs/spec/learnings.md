# learnings.md – Shared Learnings for QOC

> **Status:** Synthesis · 2026-10-26 · Decisions D-01–D-14 documented
> **Sources:**
> [`analysis/learnings/oc-open.md`](../analysis/learnings/oc-open.md) ·
> [`analysis/learnings/pi.md`](../analysis/learnings/pi.md) ·
> [`analysis/learnings/oh-my-pi.md`](../analysis/learnings/oh-my-pi.md) ·
> [`analysis/learnings/nessy.md`](../analysis/learnings/nessy.md)
>
> **Goal:** Derive a **shared, actionable backlog list** from the four analyses
> that makes QOC better, more stable, faster and production-ready.
>
> **Not the goal:** Feature parity with OpenCode, pi, oh-my-pi or Nessy.
> QOC is a Java engine with its own strengths (pure `engine.core`, append-only
> journal, SQL persistence, rule chain, specification with invariants). Copying
> without architectural review would rather damage these strengths.

---

## Contents

- [0. Executive summary](#0-executive-summary)
- [1. Comparison of the four analyses](#1-comparison-of-the-four-analyses)
- [2. Target picture: better, more stable, faster, production-ready](#2-target-picture-better-more-stable-faster-production-ready)
- [3. Prioritized measures](#3-prioritized-measures)
  - [P0 – Production blockers](#p0--production-blockers)
  - [P1 – Reliability, quality and ergonomics](#p1--reliability-quality-and-ergonomics)
  - [P2 – Performance and later differentiation](#p2--performance-and-later-differentiation)
- [4. Order and phases](#4-order-and-phases)
- [5. Measurable Definition of Done](#5-measurable-definition-of-done)
- [6. Decision log (D-01–D-14)](#6-decision-log-d-01d-14)
- [7. Source mapping and delimitation](#7-source-mapping-and-delimitation)

---

## 0. Executive summary

QOC is already architecturally further along than many references: clear
layers, a pure core module, an append-only event journal, SQLite/PostgreSQL,
claim-based execution, a broad tool palette and a specified set of invariants.
The four analyses therefore do not show "QOC is unfinished", but **where the
next levers lie**.

The most important insight from the comparison:

> **Most sources optimize feature ergonomics. Only Nessy optimizes production
> correctness. For "production ready", QOC must first close the Nessy axis –
> not feature parity.**

The biggest current gaps:

1. **Persistent side effects and approvals.** Open approvals and tool
   executions do not survive a restart cleanly (QOC-009, QOC-011).
2. **No explicit turn outcome.** Busy, Refused, Failed and Terminated are not
   distinguishable for clients (QOC-013).
3. **Compaction is not yet token- and cache-stable.** Turn boundaries have
   improved, but token budget, stable chapters and checkpoint placement are
   missing (QOC-010 partly, QOC-012).
4. **Prompt and instruction stability.** No `RULES.md`, no re-injection after
   compaction/revert, no stable block structure.
5. **Operations.** Health/readiness, metrics, audit, load/soak tests,
   backup/restore and graceful shutdown are not defined as a production gate.
6. **Tool ergonomics.** Anchored edits, scan cache and read selectors are
   missing; this costs precision, tokens and time.
7. **Orchestration.** Typed subagent batches, worktree isolation and export are
   missing; the existing task registry is a good start.

### Top-10 list

| # | Measure | Why first? |
|---|---|---|
| 1 | **Turn-outcome algebra** (QOC-013) | Without it, errors, busy and aborts are not distinguishable for clients. |
| 2 | **Idempotency key + durable tool-call lifecycle** (QOC-011) | Prevents duplicate side effects after crash/retry. |
| 3 | **Durable approvals** (QOC-009) | Open approvals must not be lost on a restart. |
| 4 | **Crash recovery and reconciliation** | Claims, tool intents and approvals must become consistent on startup. |
| 5 | **Token-aware compaction + turn integrity** | Stable context, fewer overflows, fewer retries. |
| 6 | **Stable prompt/checkpoint structure** (QOC-012) | Cache hits and reproducible prompts. |
| 7 | **Retry, timeout, cancel and turn policy** | No unbounded turns, no hanging processes. |
| 8 | **Limits, backpressure, graceful shutdown** | Overload must degrade in a controlled way, not crash. |
| 9 | **Health, metrics, audit, turn stats** | Production without observability is flying blind. |
| 10 | **Tool ergonomics: scan cache, read selectors, anchored edits** | Faster, more precise, less token consumption. |

---

## 1. Comparison of the four analyses

### 1.1 Short profiles

| Source | Strength | Most important lesson for QOC | Risk of blind copying |
|---|---|---|---|
| **OpenCode (`analysis/learnings/oc-open.md`)** | Broad agent ergonomics: context files, commands, skills, session tree, queue, settings | Instructions and sessions must be usable and resumable | Plugin/TS thinking does not fit the Java engine; mechanism first, not surface |
| **pi (`analysis/learnings/pi.md`)** | Minimal core, clear compaction and prompt-template mechanics | Token budget, turn boundaries, commands, settings, export | pi is deliberately minimal; QOC must not weaken its strict boundaries |
| **oh-my-pi (`analysis/learnings/oh-my-pi.md`)** | Tool ergonomics and context economy: Hashline, scan cache, LSP, task batches, approval tiers, artifacts | Precise edits, ignored trees, reusable tool output and typed subagents | Very broad product surface; not everything is core to the engine |
| **Nessy (`analysis/learnings/nessy.md`)** | Durable effects, outcome algebra, turn policy, chapter compaction, durable approvals, idempotency | Production correctness: work is a durable obligation, not just a step result | Agent-centric effect model must not replace QOC's tool snapshot and rule chain |

### 1.2 Topic matrix

Legend: ✅ = explicitly covered, ◐ = partial/implicit, — = not in focus.

| Topic | oc | pi | oh-my-pi | Nessy | Consensus | QOC consequence |
|---|---:|---:|---:|---:|---|---|
| Context files / prompt blocks | ✅ | ✅ | ✅ | ✅ | **4/4** | Stable blocks, `RULES.md`, imports, re-injection |
| Compaction at turn boundaries | ✅ | ✅ | ✅ | ✅ | **4/4** | Token budget + full turns + stable chapters |
| Session tree / fork / export | ✅ | ✅ | ✅ | ◐ | **3/4** | Fork/tree are present; export and branch summary missing |
| Limit and re-read tool output | ✅ | ◐ | ✅ | ◐ | **3/4** | Artifacts present; complete streaming/retention |
| Stable cache prefixes | ✅ | ✅ | ✅ | ✅ | **4/4** | Checkpoint not at index 0; static blocks first |
| Subagent orchestration | ◐ | ◐ | ✅ | ◐ | **3/4** | Typed batches, wait/cancel, isolation |
| Approval tiers / modes | ✅ | ◐ | ✅ | ✅ | **3/4** | READ/WRITE/EXEC, target-aware grants |
| Durable approvals | — | — | ◐ | ✅ | **1/4, critical** | P0: survives restart, deadline, facts |
| Idempotency / durable effects | — | — | — | ✅ | **1/4, critical** | P0: key + intent/result + reconciliation |
| Outcome algebra | ◐ | ◐ | ◐ | ✅ | **1/4, critical** | P0: Busy/Refused/Failed/Terminated |
| Turn policy | — | ◐ | ◐ | ✅ | **1/4, critical** | P0/P1: Continue/AnswerNow/FailTurn |
| Observability / trajectory | ◐ | ◐ | ✅ | ✅ | **3/4** | Turn stats, metrics, audit, trajectory later |
| Model roles / capabilities | — | ◐ | ✅ | ◐ | **2/4** | Only meaningful with a second provider |
| Memory / advisor / TTSR | — | — | ✅ | — | **1/4** | Later; `RULES.md` first |
| LSP / IDE depth | — | — | ✅ | — | **1/4** | P2; high effort, high value for code quality |
| Hashline / anchored edits | — | — | ✅ | — | **1/4** | P1; high quality lever |
| Scan cache | — | — | ✅ | — | **1/4** | P1/P2; performance lever |

### 1.3 What all four say

These points have genuine consensus and should be treated as **design principles**:

1. **Compaction must not split a tool interaction.**
   Always keep full turns; tool call and tool result belong together.
2. **Context needs a stable structure.**
   Static system/instruction blocks first, project-specific content after,
   active turn last. This is good for cache, reproducibility and debugging.
3. **Summaries are a view, not the truth.**
   The raw log stays append-only; compaction is a projection.
4. **Sessions must be resumable and branchable.**
   Tree/fork/export are not luxury features but recovery and
   debugging tools.
5. **Tool output must be limited yet fully reachable.**
   Visible prefix + stable artifact reference instead of silent truncation.
6. **Subagents need clear results and isolation.**
   No "fire and forget", no swallowed errors, no colliding worktrees.
7. **Permissions need an explicit chain.**
   `deny` before stored approval, `ask` before `allow`, abort separate from
   visible tool error.

### 1.4 What only one source says – and is P0 anyway

The consensus matrix must not be misunderstood as a vote. These points come
almost only from Nessy but are **production blockers**:

| Nessy lesson | QOC problem | Why P0 |
|---|---|---|
| Durable approvals with facts and deadline | QOC-009 | A restart must not lose an open approval; otherwise the claim hangs. |
| Idempotency key for tool calls | QOC-011 | At-least-once execution needs a stable correlation key. |
| Explicit outcome algebra | QOC-013 | Clients cannot distinguish "busy", "refused", "failed" and "terminated". |
| Staged turn policy | `maxSteps` alone | A turn can become unbounded via tool loops, tokens, time or cost. |
| Effect/outbox thinking | Claims + journal | Recovery must know what was announced, what was executed and what is open. |
| Usage/trajectory projections | OTel + token counter | Operations and cost optimization need turn statistics, not just sums. |

### 1.5 What QOC already has

According to the current QOC findings register and code state (commit `e478f19`,
partly uncommitted follow-up work):

| ID | Topic | Status | Consequence for this list |
|---|---|---|---|
| QOC-001 | Shell output and background processes | DONE | Do not re-prioritize; at most check streaming/process groups |
| QOC-002 | Compaction summary safely updated | DONE | SHA-256 and predecessor text are present |
| QOC-003 | Background subagent errors visible | DONE | Task registry is present; typed batches still missing |
| QOC-004 | Search/glob on real trees | DONE | Ignore/hidden/binary/timeout/paging are present; scan cache missing |
| QOC-005 | Tool output as artifact | DONE | `ArtifactRef`, `artifact://`, read/grep resolution present |
| QOC-006 | Session fork and tree | DONE | Fork/tree present; export/branch summary missing |
| QOC-007 | Tool catalog leak after compaction | DONE | Keep regression test in view |
| QOC-008 | PermissionDenied as HTTP 500 | DONE | Keep hardening domain-error mapping |
| QOC-009 | Ephemeral approvals | **OPEN** | **P0-03** |
| QOC-010 | Compaction cuts a turn | DONE (turn boundary) | Token budget and chapter structure remain open → **P0-05** |
| QOC-011 | No idempotency key | **OPEN** | **P0-02 / P0-04** |
| QOC-012 | Checkpoint at index 0 | **OPEN** | **P0-06** |
| QOC-013 | No outcome algebra | **OPEN** | **P0-01** |

Additionally present and to be preserved:

- TLS/Basic Auth and public reachability via Nginx (operations, not an engine
  feature).
- Native binary and systemd service.
- Broad tool palette including MCP, skills, subagents, worktrees, web.
- Contract tests and ArchUnit rules as a basis for further hardening.
- JaCoCo gate of 80 % instruction coverage.

### 1.6 Contradictions and uncertainties

- **QOC-010:** The register says `DONE` because the turn boundary was fixed.
  Token budget, cache stability and cumulative file lists are still open. This
  synthesis therefore treats QOC-010 as **partially done**.
- **QOC-007/QOC-008:** The detail sections still have some checkboxes open
  even though the overview says `DONE`. The code state is authoritative; the
  checkboxes should be updated.
- **QOC-009/011/012/013:** These four are the clear open production gaps.
- **Reference states:** `analysis/learnings/oh-my-pi.md` compares oh-my-pi `0b5c27a`
  with QOC `24f0273`; `analysis/learnings/nessy.md` compares Nessy `0c26499`. QOC has
  moved on since. Statements should be read as patterns, not as an exact file
  inventory.
- **`qoc-findings.md`:** Contains uncommitted changes. Do not overwrite it;
  this synthesis only links to it.

---

## 2. Target picture: better, more stable, faster, production-ready

### 2.1 Four quality axes

| Axis | Concrete goal | Measure |
|---|---|---|
| **Better** | Fewer retries, more precise edits, stable context, clear results | Retry rate, edit error rate, context overflows, task success rate |
| **More stable** | Crash-safe, idempotent, bounded, explicitly failing | Stuck claims, duplicate side effects, lost approvals, 24h soak |
| **Faster** | Shorter time-to-first-token, fewer tokens, fewer scans | p95 latency, tokens per turn, scan time, cache hit rate |
| **Production-ready** | Secure, observable, recoverable, overloadable | Health/metrics, backup-restore, load test, security review |

### 2.2 Non-goals (deliberately not now)

These points from the references are attractive but not current production
blockers. They belong in P2/P3 or stay out permanently:

- Full plugin/marketplace runtime (TypeScript/JS) – QOC is Java;
  MCP and a small Java SPI are the better boundaries.
- 60+ provider catalog and KDL compatibility corpus – only with real demand
  and multiple adapters.
- Snapcompact (history as images) – provider-dependent, expensive, opaque.
- Autonomous memory pipelines, advisor model and TTSR – first `RULES.md` and
  manually curated project rules.
- Persistent shells/services – background jobs and process registry suffice for
  server operation.
- Encrypted share relay / collaboration – export suffices.
- Exactly-once execution – the specification allows at-least-once; QOC should
  not over-promise.
- Full Nessy effect rebuild – first minimal reconciliation, then decide whether
  a real effect table is needed.

---

## 3. Prioritized measures

The IDs are deliberately independent of QOC-001…013 so the list can be used as
a backlog. Each measure names goal, concrete changes, acceptance and tests.

### P0 – Production blockers

#### P0-01 – Turn-outcome algebra (QOC-013)

**Why:** `PromptResult` currently carries only `finish`, text, usage and
optional structured output. A client cannot tell whether a turn never ran
(busy), was rejected by the model, failed technically or was aborted. This
makes retries and UI states unsafe.

**Concrete:**
- Introduce a sealed `TurnOutcome`:
  - `Completed(finish, usage, text, structured)`
  - `Busy(retryAfter)`
  - `Refused(reason, providerCode)`
  - `Failed(error, retryable)`
  - `Terminated(reason)`
  - `Cancelled`
- Switch `Engine.runPrompt` and `PromptResult` to it; usage only for a turn
  that actually ran.
- HTTP mapping explicit: 200 / 409 / 422 / 502–503 / 408–499; no domain
  errors as 500.
- SSE terminal frame (`turn.completed` / `turn.failed`) with the same outcome.
- Refused must **not** appear as a model-side tool error.

**Acceptance:**
- Busy, Refused, Failed, Terminated and Cancelled are distinguishable in the
  API response and in the SSE stream.
- A refused turn creates no fabricated assistant message.
- Retry decisions are documented per outcome.

**Tests:** busy session, terminated session, model refusal, provider error,
timeout, cancel, SSE terminal frame, HTTP status mapping.

**Dependencies:** none; should come first because all other error paths
benefit from it.

**Effort/risk:** M / low.

---

#### P0-02 – Idempotency key and durable tool-call lifecycle (QOC-011)

**Why:** QOC guarantees at-least-once. After a crash a tool can be executed
again. Without a stable key a side-effecting tool cannot detect that. `ToolContext`
currently has only session, agent, message and call ID.

**Concrete:**
- `IdempotencyKey` as its own value object; generated **at announcement** of
  the tool call, not only at execution.
- Carry the key in `ToolContext`, `ToolCall`, tool events, approval request
  and subagent assignment.
- Persist an intent event before the side effect; a result event after
  completion. The key connects both.
- Extend the tool contract: `readOnly` / `sideEffecting` and optionally
  `supportsIdempotency`.
- Recovery: detect intent without result; safely repeat read-only calls;
  side-effecting only with dedupe or explicitly report `Unknown`/`NeedsReview`.
- No exactly-once promise; semantics stay at-least-once.

**Acceptance:**
- A recovered tool call is uniquely identifiable via the key.
- A side-effecting tool can dedupe using the key.
- A crash between intent and result produces an explicit state, not a silent
  loss.

**Tests:** crash between intent/result, duplicate delivery, retry, key
stability across resume, approval and tool call sharing the same key, read-only
vs. side-effecting.

**Dependencies:** P0-03 (approvals) and P0-04 (recovery) share the key.

**Effort/risk:** M / medium (event/persistence change).

---

#### P0-03 – Durable approvals (QOC-009)

**Why:** `PendingApprovals` is an in-memory map. A restart with an open
approval loses the request while the execution claim keeps living. That is a
real production bug.

**Concrete:**
- Table `approval_request` (Flyway migration):
  `id`, `session_id`, `project_id`, `action`, `resource`, `tier`,
  `facts_json`, `status`, `created_at`, `deadline_at`, `decided_at`,
  `decided_by`, `decision`, `reason`, `idempotency_key`.
- Port `ApprovalRequestStore` with `create`, `pending(session)`, `decide`,
  `expire`, `find`, `recover`.
- `PermissionEngine` writes the request **before** waiting; `ApprovalSource`
  waits on store/doorbell instead of a purely local future.
- Deadline: expired requests are deterministically denied/aborted; no thread
  stays blocked indefinitely.
- HTTP/SSE: `GET /api/session/{id}/approvals`,
  `POST /api/session/{id}/approvals/{id}`, event on decision.
- Recovery: make open requests visible after restart; let orphaned requests
  expire or republish.

**Acceptance:**
- An open approval is visible after a restart.
- Deadline, facts and decision survive the restart.
- Duplicate decisions are idempotent; a decision after expiry is explicit.
- No claim hangs because of a lost approval.

**Tests:** restart with open approval, deadline, duplicate reply, reply after
expiry, cross-session isolation, approval with idempotency key.

**Dependencies:** P0-02 (key), migration, possibly P0-08 (parking instead of
blocking).

**Effort/risk:** M–L / medium.

---

#### P0-04 – Crash recovery and reconciliation

**Why:** QOC has claims and a journal, but the startup path must systematically
clarify: which claims are open? Which tool calls were announced but not
finished? Which approvals are open? Which background tasks were lost with the
process? Nessy's effect thinking supplies the right question, even though QOC
does not immediately need a full effect table.

**Concrete:**
- Define and test the startup order:
  1. Check migrations/DB availability.
  2. Load open claims.
  3. Reconcile tool intents without result.
  4. Load/expire open approvals.
  5. Mark in-memory background tasks as `LOST` (they do not survive the
     process; this must be visible).
  6. Clean the process registry.
  7. Rebuild queues/doorbell.
- Every recovery case ends in an explicit state: `Resumed`, `Failed(reason)`,
  `NeedsReview`, `Expired`.
- No silent swallowing; recovery events in the journal.
- Full effect/outbox table only decided afterwards (P1-08).

**Acceptance:**
- A crash at any defined point leads to an explicit result.
- No claim stays `RUNNING` permanently.
- Lost background tasks are visible, not "running forever".
- Recovery is idempotent (repeated startup does no harm).

**Tests:** fault injection at intent/result/approval/claim/shutdown; double
start; DB down during start; orphaned processes.

**Dependencies:** P0-02, P0-03.

**Effort/risk:** M–L / high (core stability).

---

#### P0-05 – Token-aware compaction and turn integrity

**Why:** QOC currently cuts after `keepMessages`, not by token budget. QOC-010
improved the turn boundary, but a single large turn or a huge tool result can
still blow the context window. All four analyses see compaction as a central
stability problem.

**Concrete:**
- `CompactionPolicy` with `reserveTokens`, `keepRecentTokens`,
  `triggerRatio`, `enabled`.
- Trigger **before** provider overflow: `contextTokens >
  contextWindow - reserveTokens`.
- Token estimator per message/tool result with safety margin; exact tokenizers
  later.
- Cut only between complete turns; tool call and tool result stay together;
  the last user turn stays complete.
- Store `firstKeptSeq`, `tokensBefore`, `summary`, cumulative read/modified
  files.
- Manual compaction with optional instructions.
- For a single overlong turn: turn-prefix summary + history summary
  (pi pattern), not cutting mid tool exchange.

**Acceptance:**
- No context overflow through normal long sessions.
- No compaction splits tool call and result.
- The last turn stays complete.
- The second compaction sees the first summary.
- Token budget and actual usage are traceable.

**Tests:** long tool result, one turn larger than the budget, repeated
compaction, exact boundary, cumulative file lists, manual instructions.

**Dependencies:** P0-06 (layout), QOC-002 (summary update, already DONE).

**Effort/risk:** M / medium.

---

#### P0-06 – Stable prompt/checkpoint structure (QOC-012)

**Why:** `ContextBuilder` inserts the compaction checkpoint at index 0 of the
message list. Every compaction thus changes the start of the model prompt and
can invalidate the cache prefix. Nessy and oh-my-pi show the more stable
structure: static blocks first, chapters/summary append-only, active turn last.

**Concrete:**
- Explicit prompt structure:
  1. static base prompt,
  2. skills/tool guidance,
  3. global/sticky rules,
  4. project context,
  5. completed compaction chapters (append-only),
  6. recent tail,
  7. active turn.
- Checkpoint no longer at message index 0; as its own chapter or its own
  system/context block before the tail.
- Do not rewrite old chapters on every compaction; append the new summary.
  Occasional merge is a conscious cache break.
- Date/time/cwd **at the end** of the prompt, not at the start.
- Keep cache-affinity headers and prompt cache key; make prefix stability
  measurable.

**Acceptance:**
- A compaction does not change the static prefix.
- The checkpoint is not at index 0.
- A request capture shows the expected block order.
- Cache hit rate is measured before/after compaction.

**Tests:** request capture before/after compaction, two compactions, empty
context, fork, different workspaces.

**Dependencies:** P0-05.

**Effort/risk:** M / medium (prompt layout is semantically delicate).

---

#### P0-07 – Retry, timeout, cancel and turn policy

**Why:** `maxSteps` is the only limit. A turn can become unbounded through tool
loops, slow providers, hanging processes or high cost. pi provides concrete
retry defaults; Nessy provides the turn policy.

**Concrete:**
- `RetryPolicy`: classified errors, exponential backoff with jitter, max
  retries, max delay, respect `Retry-After`. No retry if visible output was
  already committed or the error is not retryable.
- `TurnPolicy`: `Continue`, `AnswerNow`, `FailTurn` based on `maxSteps`,
  `maxTokens`, `maxWallTime`, `maxCost`.
- Timeouts per tool, shell, HTTP, MCP and subagent; hard upper bounds.
- Cancellation propagation: session interrupt → abort provider stream →
  kill process group → cancel subagent → release resources.
- No thread leak, no hanging process, no "running forever" turn.

**Acceptance:**
- A turn ends deterministically with Continue/AnswerNow/FailTurn.
- An abort terminates provider stream, tool process and subagent.
- Retry-After above the cap leads to fast failure instead of long waiting.
- No retries after already visible output.

**Tests:** provider timeout, retry-after, partial stream, cancel during tool,
cancel during shell, subagent cancel, cost limit.

**Dependencies:** P0-01 (outcome), P0-08 (limits).

**Effort/risk:** M / medium.

---

#### P0-08 – Limits, backpressure and graceful shutdown

**Why:** Without hard limits a single client or loop can exhaust the process,
the DB or the provider. Production-ready means: overload degrades in a
controlled way.

**Concrete:**
- Configurable limits: max sessions, max steps, max tool calls/turn, max
  subagents, max request bytes, max output, max context, max cost, max
  wall time.
- Admission control: 429 with `Retry-After` at saturation; bounded queues;
  fair ordering.
- Limit DB pool, HTTP connection pool and virtual-thread usage.
- Graceful shutdown: no new requests, drain or abort running turns, kill
  processes, flush events, handle claims per specification, close registry.
- No `OutOfMemoryError` from unbounded artifacts/queues.

**Acceptance:**
- Overload produces 429/backpressure, not a crash.
- Shutdown leaves no hanging process and no unclear claim.
- Memory does not grow unbounded in a 24h soak.
- Queue depth and pool utilization are visible.

**Tests:** load test, saturation, shutdown during turn/tool, process cleanup,
queue limits, memory soak.

**Dependencies:** P0-07.

**Effort/risk:** M / medium.

---

#### P0-09 – Health, metrics, audit and turn stats

**Why:** Production-ready without observability is flying blind. QOC has
OpenTelemetry spans and token counters, but no clear health/readiness contracts
and no turn projection.

**Concrete:**
- `GET /health/live` (process) and `GET /health/ready` (DB, migrations,
  workspace, provider key, disk, queue).
- Metrics: requests, sessions, turn duration, tokens, cost, tool calls,
  errors by outcome, retries, queue depth, open approvals, compaction duration,
  DB pool.
- Structured logs with `sessionId`, `turnId`, `toolCallId`,
  `idempotencyKey`; secret redaction.
- Audit events for permission decisions, tool execution and approval changes.
- `TurnStats` projection (Nessy): steps, tool calls, tokens, cost, duration,
  retries, outcome. Trajectory later.

**Acceptance:**
- Readiness fails when DB/migration/workspace are not ready.
- Every turn is findable via IDs in logs and metrics.
- No secrets in logs.
- Open approvals and queue depth are alertable.

**Tests:** health on DB failure, missing migration, missing workspace; log
redaction; metrics endpoint; trace correlation.

**Dependencies:** P0-01 (outcome), P0-08 (limits).

**Effort/risk:** M / low–medium.

---

### P1 – Reliability, quality and ergonomics

#### P1-01 – Prompt and instruction stability

**Why:** All four analyses deal with context files. QOC loads `AGENTS.md`,
`SYSTEM.md` and `APPEND_SYSTEM.md`, but there is no ledger for re-injection
after compaction/revert, no `RULES.md`, no `@path` imports and no stable
block structure.

**Concrete:**
- `RULES.md` as a sticky rule that accompanies **every** request.
- `@path` imports: relative to the importing file, `~/` and absolute, max
  5 hops, skip cycles, do not expand code fences.
- Re-injection after compaction/revert via synthetic messages with a
  metadata ledger (oc) or equivalent persistent instruction entries.
- Project context in a trailing block; static blocks before it.
- Date/time/cwd at the end.
- Deterministic order and deduplication.

**Acceptance:**
- Instructions are visible again after compaction/revert.
- Import cycles and code fences are tested.
- `RULES.md` survives long sessions.
- Block order is deterministic.

**Tests:** re-injection, import cycle, code fence, `RULES.md` across
compaction, order, deduplication.

**Dependencies:** P0-06.

**Effort/risk:** M / medium.

---

#### P1-02 – Session export and branch summary

**Status (D-11):** Export is **deferred**. There is currently no export; the
redaction policy is documented, the implementation follows only on concrete
demand. The branch summary can be implemented independently of that.

**Why:** Fork/tree are present, but there is no export. Without export,
debugging, handover and audit are weak. Branch summaries are missing when a
tree path is left.

**Concrete:**
- `GET /api/session/{id}/export?format=markdown|json` with:
  system prompt, tool definitions, transcript, tool cards, usage, artifact
  references, secret redaction.
- Branch summary on fork/tree switch (goal, decisions, files, open
  questions).
- Export tests for large sessions and redacted fields.

**Acceptance:**
- Markdown/JSON export is complete and redacted.
- A fork can produce a branch summary.
- Artifacts remain referenceable.

**Tests:** export with secrets, large tool outputs, fork, branch summary.

**Dependencies:** QOC-006 (DONE), P1-01.

**Effort/risk:** S–M / low.

---

#### P1-03 – Typed subagent batches and worktree isolation

**Why:** The background task registry exists (QOC-003), but `subagent`
still returns only text; there are no `tasks[]` batches, no `outputSchema`
validation and no isolation. oh-my-pi shows the value: parallel, typed,
conflict-free work.

**Concrete:**
- `tasks[]` with `name`, `agent`, `task`, `outputSchema`, `tools`, `cwd`,
  `isolated`.
- `SubagentResult` with `text`, optional `structured`, usage, child session.
- Reuse the `SubmitResult` mechanism for structured child results.
- Worktree only for a Git repo; return result with path and diff; **no**
  auto-merge.
- Keep depth limit and semaphore; make errors visible.

**Acceptance:**
- A batch with multiple tasks returns typed results.
- An isolated task works in its own worktree.
- Errors/cancels are visible; no silent losses.
- Depth and concurrency limits apply.

**Tests:** schema validation, one task fails, cancel, worktree cleanup,
parallel batches, depth limit.

**Dependencies:** QOC-003 (DONE), P0-01.

**Effort/risk:** M–L / medium.

---

#### P1-04 – Approval tiers, modes and target-aware grants

**Why:** QOC has a good rule chain, but `ToolOptions.permission` is a free
string, there are no READ/WRITE/EXEC tiers, no modes and no target-aware
grants. oh-my-pi and Nessy show how to make approvals more precise and safer.

**Concrete:**
- `ApprovalTier { READ, WRITE, EXEC }` on tool definitions.
- `ApprovalMode` (`ALWAYS_ASK`, `ALWAYS_ASK_CACHED`, `WRITE`, `YOLO`);
  QOC default stays `ALWAYS_ASK`.
- Extend `SavedApproval` with tier and target scope; migration.
- File grants by resolved path and selector; tree grant for recursive search.
- Shell pattern policy (allow/deny/prompt) for critical commands.
- Re-resolve approval after hook input change; fail-closed without an
  interactive channel.

**Acceptance:**
- A read grant does not allow write/exec.
- A write grant covers reads of the same target.
- A critical shell command is blocked without approval.
- A hook change triggers re-evaluation.

**Tests:** tier hierarchy, target scope, shell pattern, hook reevaluation,
headless fail-closed.

**Dependencies:** P0-03.

**Effort/risk:** M / medium.

---

#### P1-05 – Scan cache, read selectors and search ergonomics

**Why:** QOC-004 solved ignore/hidden/binary/timeout/paging. A scan cache is
still missing, and `read` has no range/raw selectors or large-file summary.
This costs time and tokens.

**Concrete:**
- `CachedFileSystem` decorator for directory walks:
  key = root + effective walk options; TTL ~1 s; 16 entries; invalidation on
  write/delete/rename by the engine.
- `read` with explicit `range`/`raw` field (no path-suffix constraint because
  of colons in filenames).
- Simple line-based summary for large files; AST summary later.
- Paging metadata instead of a silent 100-hit cap (already improved).
- Tool output with clear "partial/timed out" marking.

**Acceptance:**
- Repeated glob/grep runs in the same turn are measurably faster.
- Shell-created files become visible at most after the TTL.
- Read-range delivers exactly the desired lines.
- Large files are not pulled into context unchecked.

**Tests:** cache hit, TTL, invalidation, shell file, range, raw, large file.

**Dependencies:** QOC-004 (DONE).

**Effort/risk:** S–M / low.

---

#### P1-06 – Anchored edits / Hashline (opt-in)

**Why:** `EditTool` is exact `old`-string replacement. With repeated snippets,
moved lines and large refactorings this costs retries. oh-my-pi shows the
biggest quality lever with Hashline: content tag, seen ranges, multi-hunk,
stale detection.

**Concrete:**
- `EditStore` (session-scoped, initially in-memory): snapshot, content hash,
  seen ranges.
- `read`/`grep` emit `[path#tag]` headers and record emitted ranges.
- Parser for `PUT N.=M`, `PUT <N`, `PUT >N`, `PUT >$`, `CUT N.=M`, `REM`,
  `MV`, named registers.
- Validate all sections before writing; reject overlapping ranges; report
  stale tag with current context.
- `edit.mode` with conservative default `replace`; Hashline opt-in per
  agent/model.
- No tree-sitter block `N*` in version 1; clear error message instead of
  guessing.

**Acceptance:**
- A stale tag is rejected and returns the current tag.
- Multiple hunks in one file work without line shifting.
- Multi-file edit is prepared atomically.
- `replace` remains available as fallback.

**Tests:** stale tag, multi-hunk, repeated anchor, indentation, CRLF,
binary/symlink, multi-file, overlap.

**Dependencies:** P1-05 (seen ranges).

**Effort/risk:** L / medium–high.

---

#### P1-07 – Security hardening

**Why:** Production-ready means not only TLS. The references show attack
surfaces: SSRF via `webfetch`/MCP, path traversal, prompt injection via tool
output, secret leaks in logs, overly broad shell rights.

**Concrete:**
- SSRF protection for `webfetch` and MCP: allow/deny lists, private IPs,
  redirects, DNS rebinding, size/time limits.
- Path traversal and symlink checks for all FS tools and artifacts.
- Prompt-injection hardening: clearly delimit tool output, do not treat
  instructions from tool output as system content.
- Secret redaction in logs, traces, export and error messages.
- Rate limits and request-size limits.
- Approval fail-closed without a UI channel.
- Dependency/container scan and SBOM.

**Acceptance:**
- SSRF and traversal tests do not pass through.
- No secrets in logs/export.
- A missing approval channel blocks instead of allowing.
- Rate limits apply.

**Tests:** SSRF URLs, redirect chains, `../`, symlink, secret in prompt,
export redaction, rate limit.

**Dependencies:** P0-08, P1-04.

**Effort/risk:** M / high (security needs review).

---

#### P1-08 – Full effect/outbox table (conditional)

**Why:** Nessy's central pattern is a durable effect table with deadlines,
retries and fenced completion. QOC may **not** need it immediately; P0-04
covers the acute recovery gap. If background jobs, approvals and side effects
grow, a real outbox becomes sensible.

**Concrete (only if needed):**
- Table `effect`: `id`, `kind`, `session_id`, `state`, `attempt`,
  `available_at`, `deadline_at`, `payload`, `last_error`,
  `idempotency_key`.
- Claim with `SKIP LOCKED` (Postgres) or single-writer (SQLite).
- Fenced completion; success deletes the row; error stores attempt + error.
- Deadlines/parking instead of blocked threads.
- Backlog coalescing for wakeups.

**Acceptance:**
- An effect survives a restart and is completed exactly once (first-wins).
- Failed attempts are visible; deadlines apply.
- No duplicate side effect for the same idempotency key.

**Tests:** crash before/after claim, deadline, retry, fenced completion,
coalescing.

**Dependencies:** P0-02, P0-04.

**Effort/risk:** L / high; evaluate only after P0.

---

#### P1-09 – Model capabilities, roles and fallback

**Status (D-08):** The roles map is **deferred** (TODO). Capability gates can
be implemented independently. The roles question is revisited only with a
second adapter or a measurable cost lever.

**Why:** QOC has an OpenAI adapter and per-agent model. For multiple
providers/models, capability gates and roles (title, compaction, summary) are
missing. oh-my-pi shows the value, but a role system with only one provider is
configuration without benefit.

**Concrete:**
- `ModelCapabilities` explicit: tools, strict tool schemas, vision, reasoning,
  streaming, context/output limits.
- Adapt tool snapshot and prompt to capabilities.
- `ModelRole` map only with a second adapter: `default`, `smol`, `plan`,
  `summary`.
- Fallback chain per role, with clear retry/cost rules.
- No KDL; JSON/YAML.

**Acceptance:**
- A model without tool support gets no tool definitions.
- Title/compaction can use a cheaper model.
- Fallback applies only for classified errors.

**Tests:** capability gating, fallback, role resolution, cost limit.

**Dependencies:** P1-01, P0-07.

**Effort/risk:** M / medium.

---

#### P1-10 – Hook SPI and lifecycle observer

**Why:** QOC has only `beforeToolCall`, `afterToolCall` and
`evaluatePermission`. For integrations, audit and tests, lifecycle and context
hooks are missing. Without an SPI every extension is a fork.

**Concrete:**
- Java SPI in `engine.core`:
  - `ToolHook` (existing, refine),
  - `SessionLifecycleHook` (created/forked/archived),
  - `ContextHook` (additive blocks only, no arbitrary mutation),
  - `CompactionHook` (before/after),
  - `StepObserver` (read-only metrics).
- Implementations in the Quarkus adapter via CDI/ServiceLoader.
- Hooks fail-closed; re-check approval after input change.
- Read-only event stream for telemetry; journal stays immutable.

**Acceptance:**
- A hook can extend context additively but cannot bypass a security decision.
- A throwing hook blocks safely.
- Audit can observe tool/session events.

**Tests:** hook order, exception fail-closed, approval reevaluation, observer
mutation prevented.

**Dependencies:** P0-09.

**Effort/risk:** M / medium.

---

#### P1-11 – Commands, skills, slash commands and queue UI

**Why:** oc/pi show many small ergonomics gaps that are individually cheap:
command macros, `/reload`, `/export`, `/session`, `/name`, `/tree`, `/fork`,
`/skill:<id>`, `/copy`, skill validation, watcher and a visible message
queue.

**Concrete:**
- `CommandLoader` for `command/*.md` and `.opencode/command/`, `$ARGUMENTS`.
- `/reload` for skills/instructions/settings.
- Skill validation (name, description) as warnings; ignore files and Git-root
  walk.
- Watcher or polling for skills/instructions.
- Queue: `delivery` in the prompt request, UI display, steer/follow-up.
- Slash commands per the oc/pi list.

**Acceptance:**
- A command macro expands correctly.
- `/reload` reloads skills/instructions.
- Queue shows pending messages; steer interrupts cleanly.
- Skill warnings are visible but do not block.

**Tests:** command substitution, reload, skill validation, queue/steer, slash
endpoints.

**Dependencies:** P1-01.

**Effort/risk:** M / low.

---

#### P1-12 – Images and attachments

**Why:** `ModelContent.Media` exists, but UI and `ReadTool` are text-oriented.
For screenshot/design work this is a gap, but not production-critical.

**Concrete:**
- Paste/drag → Base64/Blob; MIME allow list; size/dimension limits;
  auto-resize.
- `images.blockImages` as a setting.
- Blob store with SHA-256; session reference.
- Read tool can return images as MediaContent.

**Acceptance:**
- An image is validated, resized and sent as MediaContent.
- Blocking images works.
- No unbounded image in context.

**Tests:** MIME, size, resize, block, blob reference.

**Dependencies:** P1-05.

**Effort/risk:** M / low–medium.

---

### P2 – Performance and later differentiation

#### P2-01 – LSP tool

**Why:** QOC currently cannot answer "who calls this?", "is this correct?" or
"rename the symbol". oh-my-pi shows the value, but LSP is a large adapter
and only one source.

**Concrete:**
- `LanguageServer` port in `engine.core`; LSP4J or a minimal JSON-RPC client
  in the adapter.
- Actions: `diagnostics`, `definition`, `references`, `symbols`, `hover`,
  `rename`, `rename_file`, `code_actions`, `status`, `reload`.
- Diagnostics after edit as warning, no auto-rollback.
- Fake LSP server for stable tests; real servers only in integration.
- Approval: navigation = READ; rename/code action = WRITE.

**Acceptance:**
- Diagnostics/definition/references work with the fake server.
- Rename returns a WorkspaceEdit; applying is explicit.
- No test depends on an installed language server.

**Tests:** fake LSP, diagnostics, rename, timeout, missing server.

**Dependencies:** P1-06 (for rename integration).

**Effort/risk:** L / medium–high.

---

#### P2-02 – Memory, advisor and TTSR (later)

**Why:** oh-my-pi shows powerful but risky mechanisms: autonomous memory
extraction, reviewer model, stream rules. For QOC, `RULES.md` (P1-01) is the
better 80/20 lever first.

**Concrete:**
- Memory: manually curated project memory + `RULES.md`; autonomous extraction
  only after a privacy/retention decision.
- Advisor: separate read-only subagent with its own model and note channel;
  no approval rights.
- TTSR: provider abort/retry + rule language + audit; high complexity.

**Acceptance:**
- Memory is deletable, redacted and token-bounded.
- Advisor can block but not approve.
- TTSR loops are bounded and auditable.

**Tests:** retention, redaction, advisor note, TTSR abort/retry.

**Dependencies:** P1-10, P1-09.

**Effort/risk:** L / high; deliberately later.

---

#### P2-03 – Snapcompact / vision compaction

**Why:** Only useful with a vision model and a clear cost model. Not a
production lever for QOC at present.

**Concrete:** Archive history as a bitmap only when a vision provider is
configured; otherwise not.

**Acceptance:** Opt-in, cost-bounded, not default.

**Effort/risk:** L / high.

---

#### P2-04 – Multi-provider catalog and capability policy

**Why:** QOC has an OpenAI adapter. A large catalog is a product in itself;
only with concrete demand.

**Concrete:** Provider adapters incrementally, capabilities (P1-09),
JSON/YAML policy, no KDL dependency.

**Acceptance:** A second adapter passes the same contract tests.

**Effort/risk:** L / medium.

---

#### P2-05 – Persistent shells / managed services

**Why:** Only needed if QOC really wants to offer interactive terminals or
long-lived services. For server operation, background jobs, process registry
and artifacts suffice.

**Concrete:** Extend process registry, output sink, readiness probe,
session logs; PTY only with UI.

**Acceptance:** Jobs are observable, killable and do not survive a session
without explicit policy.

**Effort/risk:** M–L / medium.

---

#### P2-06 – Pure sealed AgentState (incremental)

**Why:** Nessy's pure state core is attractive, but a big rebuild. QOC is
already event-sourced; a rewrite would be risky.

**Concrete:** Encapsulate invariants step by step in a pure evaluator;
replay tests; no big-bang migration.

**Acceptance:** Replay is deterministic; event order is checked centrally.

**Effort/risk:** L / high; only after P0/P1.

---

## 4. Order and phases

The order follows not feature parity but production correctness.
P0-01/02/03/04 are the base; P0-05/06 and P0-07/08/09 can run after P0-02 in
parallel.

### Phase 0a – API and execution correctness

1. **P0-01** Turn-outcome algebra.
2. **P0-02** Idempotency key + tool-call lifecycle.
3. **P0-03** Durable approvals.
4. **P0-04** Crash recovery/reconciliation.

**Gate:** Crash matrix green; no lost approvals; no duplicate side effects;
Busy/Refused/Failed/Terminated distinguishable; no 500 for domain errors.

### Phase 0b – Context and runtime

5. **P0-05** Token-aware compaction.
6. **P0-06** Stable prompt/checkpoint structure.
7. **P0-07** Retry/timeout/cancel/turn policy.
8. **P0-08** Limits/backpressure/graceful shutdown.
9. **P0-09** Health/metrics/audit.

**Gate:** No context overflow in long tests; no split turns; cache prefix
stable; 24h soak; load test; health/metrics; restore tested.

### Phase 1 – Context, sessions, subagents

10. **P1-01** Prompt/instruction stability.
11. **P1-02** Export + branch summary.
12. **P1-03** Typed subagent batches + isolation.
13. **P1-04** Approval tiers/modes.
14. **P1-05** Scan cache/read selectors.

**Gate:** Instructions survive compaction/revert; export redacts; batches
typed; tier grants correct; scan cache measurable.

### Phase 2 – Tool quality and security

15. **P1-06** Anchored edits.
16. **P1-07** Security hardening.
17. **P1-08** Effect/outbox (only if needed).
18. **P1-09** Model capabilities/roles.
19. **P1-10** Hook SPI.
20. **P1-11** Commands/skills/queue.
21. **P1-12** Images.

**Gate:** Edit error rate drops; security review; hooks fail-closed;
commands/queue work.

### Phase 3 – Differentiation

22. **P2-01** LSP.
23. **P2-02** Memory/advisor/TTSR.
24. **P2-03** Snapcompact.
25. **P2-04** Multi-provider.
26. **P2-05** Persistent shells.
27. **P2-06** Pure state core.

**Gate:** Start only when a concrete product need and a cost/security model
exist.

---

## 5. Measurable Definition of Done

### 5.1 Reliability

| Metric | Target | Evidence |
|---|---|---|
| Stuck claims after crash | 0 | Fault-injection matrix |
| Lost open approvals | 0 | Restart test |
| Duplicate side effects with same key | 0 | Dedupe test |
| Domain errors as HTTP 500 | 0 | HTTP contract tests |
| Indistinguishable turn outcomes | 0 | API/SSE tests |
| Recovery without explicit state | 0 | Recovery events |

### 5.2 Stability

| Metric | Target | Evidence |
|---|---|---|
| 24h soak | stable, no OOM, no thread leak | Load/soak test |
| Queue depth | bounded | Metric/alarm |
| Memory growth | < defined threshold | Soak |
| Context overflow rate | < 0.1 % | Provider errors + compaction |
| Compaction splits turn | 0 | Property/regression tests |
| Graceful shutdown | no hanging processes | Shutdown test |

### 5.3 Speed

| Metric | Target | Evidence |
|---|---|---|
| p95 time-to-first-token | defined SLO | Metric |
| p95 turn duration | defined SLO | Metric |
| Tokens per turn | decreasing via compaction/pruning | Usage projection |
| Scan time on large trees | decreasing via cache | Benchmark |
| Cache hit rate | increasing, measurable | Provider headers/usage |
| Edit retry rate | decreasing via anchored edits | Tool statistics |

### 5.4 Production-readiness

| Criterion | Evidence |
|---|---|
| Health/readiness | `/health/live`, `/health/ready` |
| Metrics/alarms | Prometheus/Micrometer, dashboards |
| Audit | Permission/tool/approval events |
| Backup/restore | documented and tested |
| Migration/rollback | Flyway test, restore test |
| Security | SSRF/traversal/secret review |
| Load/soak | documented results |
| Deployment/rollback | idempotent, versioned, health check |
| Docs | Runbook, SLOs, incident path |

Existing gates remain:

- `mvn test` green.
- JaCoCo ≥ 80 % instructions.
- Contract tests on PostgreSQL/Testcontainers.
- ArchUnit rules green.
- No tests from the workspace root.

---

## 6. Decision log (D-01–D-14)

> **Status:** 2026-10-26. The decisions are documented as defaults for
> implementation. D-08 and D-11 are deliberately deferred and marked TODO.

| ID | Topic | Decision | Consequence |
|---|---|---|---|
| **D-01** | Durable work | **Reconciliation first**, no full outbox now | P0-04 built first; P1-08 only on trigger |
| **D-02** | Approval waiting | **Park with doorbell**; state `WaitingApproval` | P0-03/P0-04; no blocked thread; deadline + recovery |
| **D-03** | Checkpoint model | **Append-only chapters**; merge at 8 chapters or 20 % context | P0-06; QOC-012; cache prefix stays stable |
| **D-04** | Token estimator | **Heuristic + 10–15 % margin**, calibrated with usage | P0-05; tokenizer only when needed |
| **D-05** | Hashline | **Opt-in**; `replace` stays default | P1-06; default switch only after metrics |
| **D-06** | Tag width | **8 hex + SHA-256 in EditStore**; tag is index | P1-06; 12 hex later for very large workspaces |
| **D-07** | LSP | **LSP4J in adapter**; core stays free of LSP details | P2-01; fake-server tests |
| **D-08** | Model roles | **Deferred / TODO**; no roles map now | P1-09 stays backlog; trigger see below |
| **D-09** | Multi-tenancy | **Single-tenant explicitly**; design `tenant_id`-capable | Authz/isolation; re-evaluate before multi-user operation |
| **D-10** | Retention/RPO/RTO | **Production defaults**, configurable per project | P0-09/P1-07; test env 24h RPO / 2h RTO |
| **D-11** | Export | **No export currently**; document redaction duty | P1-02 stays backlog; future export redacts by default |
| **D-12** | Worktree isolation | **Diff/review**, no auto-merge | P1-03; parent checks and applies |
| **D-13** | Exactly-once | **At-least-once + idempotency key** | P0-02; tools document idempotency |
| **D-14** | Persistent shells | **No**, background jobs suffice | P2-05 optional; only with UI and approval |

### D-01 – Reconciliation first, outbox later

**Decision:** P0-04 builds a startup reconciliation over journal, claims, tool
intents, approvals and background tasks. A full Nessy outbox is **not**
introduced now.

**Rationale:** QOC already has a journal and claim store. An additional effect
table would initially be a second source of truth and a large rebuild. The
acute gap is not "no outbox" but "open work is not systematically reconciled
on startup".

**Trigger for re-evaluation:** multiple background work types, real
cross-process workers, scheduled retries with deadlines or approval parking at
larger scale. Then evaluate P1-08 (hybrid/outbox).

### D-02 – Park approvals with doorbell

**Decision:** A turn waiting for an approval moves into an explicit state
`WaitingApproval`. The approval request is persisted; execution is woken via
doorbell. No thread blocks indefinitely.

**Rationale:** QOC-009 requires restart safety. A blocked virtual thread is
cheap but not durable and hard to drain. Parking fits graceful shutdown,
deadline and recovery.

**Consequence:** P0-03 and P0-04 must be considered together; the claim is
preserved but the state is visible and resumable.

### D-03 – Compaction as append-only chapters

**Decision:** Compaction summaries are appended as chapters; the static prompt
prefix stays stable. A merge happens only at a threshold (start: 8 chapters or
20 % of context budget).

**Rationale:** A checkpoint at index 0 invalidates the cache prefix on every
compaction. Chapters are auditable, more cache-friendly and fit
"compaction is a view, not the truth".

**Consequence:** P0-06 replaces QOC-012; QOC-012 can be closed after
implementation.

### D-04 – Token estimation by heuristic

**Decision:** Preflight estimation with UTF-8 characters / 3.5–4 plus 10–15 %
safety margin. Actual provider usage calibrates the estimate. An exact
tokenizer is added only when misestimation is proven.

**Rationale:** The decision "is compaction needed?" does not require
character-level tokenizer accuracy. A conservative heuristic is
dependency-free and sufficient; the tokenizer stays an adapter.

**Consequence:** P0-05 documents the formula and calibration.

### D-05 – Hashline opt-in

**Decision:** `edit.mode=replace` stays the default. Hashline is activated per
agent or model. A default switch happens only once metrics (edit retry rate,
error rate) prove the benefit.

**Rationale:** Hashline is a new prompt and tool contract format. It can
greatly improve quality but increases prompt surface and can overwhelm smaller
models.

**Consequence:** P1-06 implements the mode without destabilizing the default.

### D-06 – 8-hex tags with store identity

**Decision:** The visible tag has 8 hex characters. Identity lives in the
`EditStore` with full SHA-256; the tag alone is **not** proof of content. For
very large workspaces it can later be raised to 12 hex.

**Rationale:** 4 hex has only 65,536 values and is too weak as a content
address. 8 hex is a good compromise; resolution always goes through the store.

**Consequence:** P1-06 explicitly tests collisions and stale tags.

### D-07 – LSP4J in the adapter

**Decision:** The LSP adapter uses LSP4J; `engine.core` sees only a neutral
`LanguageServer` port. LSP stays P2 and is tested with a fake server.

**Rationale:** LSP includes capability negotiation, document sync, diagnostics,
workspace edits and lifecycle. LSP4J is established and keeps the core layer
clean. A minimal client only if dependency size or audit requirements argue
against it.

**Consequence:** P2-01; real language servers only in integration tests.

### D-08 – Model roles: deferred (TODO)

**Decision:** No full model-roles map is introduced **now**. The point stays
documented as TODO.

**Rationale:** With only one provider a large roles map is configuration
without a real lever. Capability gates are useful independently, but the
roles question is decided only with a second adapter or a measurable cost
lever (summary/title).

**Trigger for re-evaluation:**
- a second provider adapter exists,
- a summary/title role saves measurable cost,
- per-agent model selection is no longer sufficient.

**Consequence:** P1-09 stays in the backlog; no premature roles configuration.

### D-09 – Single-tenant explicitly

**Decision:** QOC is initially **single-tenant**. Data models are designed so
`tenant_id` can be added later; current isolation is one workspace/owner.

**Rationale:** The four analyses do not cover multi-tenancy. For the current
Basic-Auth operation single-tenant is probably correct. An implicit "maybe
later" would be risky because sessions, artifacts, approvals and backups all
need tenant boundaries.

**Trigger for re-evaluation:** multi-user operation, multiple customers,
separate data storage or per-user quotas.

**Consequence:** Authz/isolation is documented and tested; multi-tenancy is
not a silent feature but its own migration.

### D-10 – Production retention and recovery defaults

**Decision:** Configurable defaults:

| Data | Default |
|---|---|
| Sessions/events | Until explicit deletion |
| DB backups | Daily full, 14 days; plus WAL/hourly |
| Artifacts | 30 days after session end or explicit |
| Logs | 14 days |
| LLM traces | 7 days |
| Exports | On-demand, not stored permanently |
| **RPO** | Production 1 h; test 24 h |
| **RTO** | Production 1 h; test 2 h |

**Rationale:** QOC stores prompts, tool output, logs and credentials. Retention
is a security and cost decision, not a storage detail.

**Consequence:** P0-09/P1-07 document the defaults and make them configurable
per project.

### D-11 – Export: not currently implemented, redaction duty documented

**Decision:** **No export** is built as part of the current P0 work. The
requirement is documented: a future export (P1-02) redacts secrets by default;
raw export only as an admin action with audit.

**Rationale:** There is currently no export. A redaction implementation
without an export would be speculative, but the policy must be set before the
first export implementation.

**Consequence:** P1-02 stays in the backlog. The first export implementation
must bring the redaction tests from P1-07.

### D-12 – Worktree isolation with diff/review

**Decision:** An isolated subagent returns worktree path, diff and test
result. The parent checks and applies. **No auto-merge.**

**Rationale:** Parallel agents quickly create conflicts; an auto-merge can
take broken code without review. Review fits QOC's approval model.

**Consequence:** P1-03; auto-merge only later with explicit approval and clean
tests.

### D-13 – At-least-once plus idempotency key

**Decision:** QOC promises **at-least-once**, not exactly-once. Every tool call
gets a stable idempotency key; tools document whether they are idempotent and
use the key for dedupe.

**Rationale:** Exactly-once is not honestly promiseable across crash boundaries
and external side effects without transactional foreign systems. The
specification already rejects it.

**Consequence:** P0-02; tool contract and recovery must carry the key.

### D-14 – No persistent shells

**Decision:** QOC introduces **no persistent shells**. Background jobs, process
registry and artifacts cover server operation. Optional session shells or
managed services only on concrete product demand.

**Rationale:** Persistent shells bring state, security risks and cleanup
problems. The current background-job approach is safer and more maintainable.

**Consequence:** P2-05 stays optional; no P0/P1 dependency.

### 6.1 Consequences for the phases

| Phase | Decisions |
|---|---|
| **Phase 0a** | D-01, D-02, D-13 |
| **Phase 0b** | D-03, D-04, D-10 |
| **Phase 1** | D-05, D-06, D-09, D-12 |
| **Phase 2** | D-07 |
| **Deferred** | D-08, D-11 |

### 6.2 Still to be concretized

- **D-08:** TODO "check model roles" with trigger second provider / cost
  lever.
- **D-11:** TODO "export redaction" when starting P1-02.
- **D-10:** concrete backup/WAL configuration for SQLite and PostgreSQL.
- **D-09:** document authz/isolation even though single-tenant.

---

## 7. Source mapping and delimitation

### 7.1 Mapping to QOC findings

| QOC ID | Status | This list |
|---|---|---|
| QOC-001 | DONE | No re-prioritization; P2-05 optional |
| QOC-002 | DONE | Basis for P0-05 |
| QOC-003 | DONE | Basis for P1-03 |
| QOC-004 | DONE | Basis for P1-05 |
| QOC-005 | DONE | Basis for P1-02 |
| QOC-006 | DONE | Basis for P1-02 |
| QOC-007 | DONE | Guardrail; keep regression test |
| QOC-008 | DONE | Guardrail; outcome mapping P0-01 |
| QOC-009 | OPEN | **P0-03** |
| QOC-010 | DONE (turn boundary) | **P0-05** for token budget/chapters |
| QOC-011 | OPEN | **P0-02 / P0-04** |
| QOC-012 | OPEN | **P0-06** |
| QOC-013 | OPEN | **P0-01** |

### 7.2 What the four analyses do not cover

These points matter for "production ready" but are not the focus of the
reference analyses:

- Multi-tenancy, authz beyond Basic Auth, quotas.
- SLOs/SLIs, alerting, runbooks, incident process.
- Backup/restore, RPO/RTO, migration rollback.
- Load, soak and chaos tests.
- Supply-chain security, SBOM, dependency scan.
- Data retention, deletion concept, data protection.
- Cost control via turn/session budgets.
- Deployment rollback and canary/blue-green.

These topics are deliberately anchored as a **production gate** in P0-08/P0-09
and in the Definition of Done, not as feature backlog.

### 7.3 Delimitation

- This file is a **synthesis and backlog template**, not an implementation
  approval.
- It neither overwrites `qoc-findings.md` nor the specification.
- It changes no architectural rules: `engine.core` stays free of Quarkus,
  Jackson, CDI and HTTP; adapters stay in `engine.adapter.*`.
- No commits, branches or milestone changes without explicit instruction.
- The four analyses remain the detail sources; this file prioritizes and
  connects them.

---

*Created: 2026-10-26 · Synthesis from `analysis/learnings/oc-open.md`,
`analysis/learnings/pi.md`, `analysis/learnings/oh-my-pi.md`,
`analysis/learnings/nessy.md` and the current QOC findings register.*