# nessy-learnings.md – What QOC can learn from Nessy

> **Status:** Analysis · 2026-10-26
> **Reference:** `nessy` at `/tmp/opencode/nessy`, commit `0c26499`
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus / LangChain4j),
> `qoc` at current `main`
> **Source scope:** this document is based on the cloned `nessy` repository and a
> comparison with the QOC implementation.

Nessy is not a coding assistant. It is a durable agent engine built around a
sealed state machine, an effect table, and a turn-based inference pipeline.
QOC should not copy Nessy wholesale. QOC already has stronger boundaries in
some places: SQLite/PostgreSQL persistence, auth, OS hardening, a broad tool
set, and a written specification with invariants.

The useful lessons are architectural. Nessy treats external work as a durable
work queue, makes turn outcomes explicit, separates fast answer pressure from
failure pressure, and keeps context assembly stable. Those are areas where QOC
is currently weaker.

---

## Contents

- [1. Nessy in one paragraph](#1-nessy-in-one-paragraph)
- [2. Priority list](#2-priority-list)
- [3. Learning details](#3-learning-details)
- [4. What QOC should not copy](#4-what-qoc-should-not-copy)
- [5. Mapping to QOC findings](#5-mapping-to-qoc-findings)
- [6. Recommended order](#6-recommended-order)
- [7. Open decisions](#7-open-decisions)

---

## 1. Nessy in one paragraph

Nessy models an agent as a pure, sealed state machine (`AgentState`). Commands
produce events and effects. Effects are written into a database table and
performed later by a dispatcher. Tool calls, approvals, and inference are all
expressed as effects with deadlines, retries, and idempotency keys. Context is
assembled from closed chapter summaries plus a bounded tail of completed turns.
Approvals are durable, addressable requests with facts and deadlines. Turn length
is governed by a policy that can ask the model to answer now or fail the turn.

---

## 2. Priority list

| Priority | Learning | Nessy anchor | QOC gap |
|---|---|---|---|
| P0 | Durable effect table / outbox | `Effects`, `JdbcEffects` | Side-effects are not a durable work queue |
| P0 | Explicit outcome algebra | `AgentState`, outcome records | `PromptResult` exposes only finish/text/usage |
| P1 | Staged turn policy | `TurnPolicy` | `maxSteps` only |
| P1 | Turn-boundary chapter compaction | `ContextAssembler` | Message-count split, checkpoint at index 0 |
| P1 | Durable approvals with facts/deadline | `ApprovalRequest` | In-memory only |
| P1 | Typed tool calls with idempotency key | `ActionRequest.ToolCall`, `IdempotencyKey` | No idempotency key |
| P2 | Backlog coalescing and leases | `JdbcEffects` | Inbox delivery is simpler |
| P2 | Usage and trajectory projections | `TurnStats`, trajectory records | Spans and usage, no trajectory model |
| P2 | Sealed pure agent state | `AgentState` | Event-sourced, but no pure sealed core |
| P3 | Approval enricher / carrier model | `ApprovalRequest` | Metadata exists, but no carrier/enricher pattern |

---

## 3. Learning details

### 3.1 Durable effect table / outbox

Nessy's central mechanism is the effect table:

- `nessy-backend/spi/.../effect/Effects.java`
- `nessy-backend/jdbc/.../jdbc/JdbcEffects.java`

An effect is written in the same transition that decided it. It later becomes
due, is claimed with `SKIP LOCKED`, and is completed by a fenced
compare-and-act operation. Failed attempts are stored on the row. Deadlines and
parking are first-class. Success deletes the row, which makes completion
first-wins without an explicit fence.

**What QOC can learn**

- Side-effecting work should be a durable obligation, not only a step result.
- Retries should carry what previous attempts learned.
- Deadlines and parking are better than blocking a thread.
- Completion should be fenced and first-wins.

**QOC fit**

QOC already has a claim store and recovery. A full effect table would be a
larger step. The realistic first step is to make tool-call execution durable
enough that recovery can see what was announced and what finished. That maps to
QOC-011 and a future approval/effect subsystem.

**Risk**

Nessy's effect model is agent-centric. QOC's tool model is provider-centric.
A direct port would blur QOC's tool snapshot and permission boundaries.

### 3.2 Explicit outcome algebra

Nessy distinguishes outcomes explicitly:

- `AgentState` accepts `InferenceAnswered`, `InferenceRefused`, `InferenceFailed`,
  `TurnStopped`.
- `TurnDecision` has `Continue`, `AnswerNow`, `FailTurn`.

QOC currently reduces this to a finish string in `PromptResult`.

**What QOC can learn**

- Busy, refused, failed, and terminated turns should be distinguishable in the
  API.
- A refused turn must not become a model-facing error.
- A turn that must answer now is a separate outcome, not a prompt hack.

**QOC fit**

This maps directly to QOC-013. QOC should introduce a sealed prompt/turn
outcome before adding more client-facing retry logic.

### 3.3 Staged turn policy

Nessy's `TurnPolicy`:

- `nessy-api/.../TurnPolicy.java`

It consults `TurnStats` and can return:

- `Continue`
- `AnswerNow`
- `FailTurn`

The shipped default bounds model calls with two thresholds.

**What QOC can learn**

- A turn should have an answer pressure and a hard failure pressure.
- Tool calls should not make a turn unbounded.
- Policy should be a function, not frozen state.

**QOC fit**

QOC has `maxSteps`, but no staged policy. A `TurnPolicy` equivalent could be
added to `Engine.runPrompt` without changing the provider contract. This is a
good P1 candidate.

### 3.4 Turn-boundary chapter compaction with stable cache prefix

Nessy's context assembler:

- `nessy-engine/.../inference/ContextAssembler.java`

It builds context in strata:

1. closed chapter summaries
2. bounded tail of completed turns
3. active turn
4. memory
5. state
6. ambient context

Summaries are stable. The active turn is always last. A chapter whose summary is
not written is not shown, and its turns remain whole in the tail.

**What QOC can learn**

- Compaction should cut between turns, never inside a tool interaction.
- Stable strata improve cache affinity.
- Summaries should be appended as closed chapters, not inserted at index 0.

**QOC fit**

This maps directly to QOC-010 and QOC-012. QOC already has compaction epochs
and summaries; the change is in boundary selection and prompt layout.

### 3.5 Durable approvals with facts and deadlines

Nessy's approval request:

- `nessy-api/.../tool/ApprovalRequest.java`

It carries:

- agent type and agent id
- turn id
- call id
- idempotency key
- tool name and arguments
- action text
- asked-at and deadline
- mutable facts added by enrichers

Approvals can be deferred and survive outside the process.

**What QOC can learn**

- An approval request should be a durable, addressable object.
- It should carry facts, not only a message.
- It should have a deadline.
- The same idempotency key should appear on approval and tool execution.

**QOC fit**

This maps to QOC-009. QOC's approvals are ephemeral and lost on restart. Nessy
shows the shape, but QOC should keep its simpler rule chain and project-wide
stored approvals.

### 3.6 Typed tool calls with idempotency keys

Nessy separates:

- `CallId`: what the model called the call
- `IdempotencyKey`: Nessy's own stable key

The idempotency key is created when the model request is recorded and reused
for retries, approvals, and tool execution.

**What QOC can learn**

- A tool call needs a stable key independent of the provider call id.
- The key should be created at call announcement, not at execution.
- Approval and tool execution should share the key.

**QOC fit**

This maps directly to QOC-011. QOC's `ToolContext` and durable tool events
should carry it. QOC must keep its at-least-once guarantee and should not
promise exactly-once execution.

### 3.7 Backlog coalescing and leases

Nessy's effect table uses:

- `SKIP LOCKED`
- due-time comparison
- fenced completion
- no leases, only actionable-at times

This makes recovery simple: a row past its deadline becomes eligible again.

**What QOC can learn**

- A work queue can recover without complex lease renewal.
- Coalescing wakes avoids duplicate passes.
- Actionable-at semantics are simpler than lease expiry.

**QOC fit**

QOC has inbox delivery and claims. A full backlog redesign is not needed now.
The lesson is useful for background subagents, shell jobs, and future approval
parking.

### 3.8 Usage and trajectory projections

Nessy tracks:

- turn stats
- inference attempts
- trajectory state
- usage

This makes long-running agent behavior observable and comparable.

**What QOC can learn**

- Usage should be a projection, not only a counter.
- Trajectory fingerprints can detect repeated or novel behavior.
- Turn-level stats are more useful than aggregate token counts.

**QOC fit**

QOC has OpenTelemetry spans and token counts. A trajectory model is a natural
later improvement, not a current defect.

### 3.9 Sealed pure agent state

Nessy's `AgentState` is pure and deterministic. Replay is a loop over events.
Only `Idle` accepts external commands.

**What QOC can learn**

- A pure state core makes replay trustworthy.
- Guarding event order in one place prevents silent replay bugs.
- External commands should be refused when the agent is busy.

**QOC fit**

QOC is event-sourced but not a pure sealed core. This is a large architectural
change and should be considered only after the current findings are stable.

---

## 4. What QOC should not copy

- Nessy's agent-centric effect model should not replace QOC's tool snapshot.
- Nessy's approval lifecycle should not override QOC's rule chain or stored
  project-wide approvals.
- QOC's specification explicitly rejects exactly-once execution. Nessy's
  idempotency keys should be adopted as correlation tokens, not as a promise.
- QOC should keep its SQLite/PostgreSQL support, auth, OS hardening, and
  broader tool set.
- QOC's `engine.core` purity is a strength. Nessy's looser architecture should
  not weaken it.

---

## 5. Mapping to QOC findings

| Nessy learning | QOC finding | Status |
|---|---|---|
| Tool catalog filter leak after compaction | QOC-007 | DONE |
| PermissionDenied as HTTP 500 | QOC-008 | DONE |
| Compaction can split a turn | QOC-010 | DONE |
| Ephemeral approvals | QOC-009 | OPEN |
| No idempotency key | QOC-011 | OPEN |
| Checkpoint at index 0 | QOC-012 | OPEN |
| No outcome algebra | QOC-013 | OPEN |

---

## 6. Recommended order

1. Finish QOC-011: add `IdempotencyKey` and durable tool-call visibility.
2. Finish QOC-009: durable approvals with facts and deadlines.
3. Finish QOC-013: sealed prompt/turn outcome.
4. Adopt staged `TurnPolicy` in `Engine.runPrompt`.
5. Rework compaction boundaries and checkpoint placement.
6. Evaluate backlog coalescing for background work.
7. Consider trajectory projections and pure sealed state later.

---

## 7. Open decisions

1. Should QOC introduce a durable effect table at all, or only durable tool-call events?
2. Should approval requests become a durable, addressable aggregate?
3. Should `TurnPolicy` be configurable per agent or global?
4. Should compaction summaries become closed chapters instead of one checkpoint?
5. Should QOC expose trajectory data in its HTTP API?
6. Should the pure sealed state model be a future rewrite or an incremental refactor?
