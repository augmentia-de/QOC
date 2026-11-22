# Plan: Asynchronous Approvals (Option B – Suspend & Resume)

> **Status:** Partially implemented (2026-11-22) — Phase 1 (infrastructure) done; Phase 2 (suspend/resume) partially done; Phase 3 (granular approval) not started.
> **Goal:** Approvals (HITL) should be possible asynchronously via external channels (email),
> without blocking the tool-call thread indefinitely.

---

## 1. Motivation

Today approvals are **synchronous/blocking**:

- `PermissionEngine.assertAllowed` calls `ApprovalSource.request(...)` and
  blocks the tool-call thread for up to 300 s (`WebApprovalSource`).
- The `CompletableFuture` is in-memory; the request is however persisted in
  `pending_approval`.
- For email (hours/days) this is insufficient.

## 2. Architecture Options

### Option A – Long blocking wait (minimal)

`ApprovalSource.request` polls the DB instead of an in-memory future, with
a configurable timeout (hours/days).

- Pro: little code, reuses existing table.
- Con: holds thread + connection; problematic for very long wait times.
- Effort: low-medium.

### Option B – Suspend & Resume (clean, scalable) ← **chosen**

On `ASK`: persist approval, mark session as `suspended`, tool call returns a
`Paused` result, turn ends. On approval (email link → endpoint) the session
is woken and the tool call is executed again.

- Pro: no long-lived connections, scalable, fits with `SessionWake`.
- Con: tool must be idempotent (re-execution), session state must be
  restored.
- Effort: high.


## Implementation Status (2026-11-22)

### Implemented (Phase 1 — Infrastructure)
- **DurableApprovalSource** — token-based approval links, polling, `resolve()`, `sessionIdForToken()`
- **EmailNotificationChannel** — SMTP email notification
- **DB migration V5** — `token`, `channel` columns + unique index on `pending_approval`
- **Token-based approval endpoints** — `GET/POST /api/approval/{token}` in `HttpApi`
- **Config keys** — `engine.approval.channel`, `engine.approval.email.*`, `engine.approval.timeout-hours`, `engine.approval.token-ttl-days`
- **DurableQuestionSource** — analogous durable question source

### Implemented (Phase 2 — Suspend & Resume, partial)
- **ToolOutcome.Paused** — `record Paused(String approvalId, String approvalUrl)`
- **StepResult.Paused** — handled in QocEngine (returns `EngineResult.paused()`)
- **EngineResult.paused()** — paused result propagation
- **`/api/session/{id}/resume`** endpoint — `SessionResource.resume()`
- **Approval reply wakes session** — `HttpApi.replyApproval` spawns thread to resume
- **`/api/approval/{token}/reply`** — resolves approval and wakes session

### Not implemented (Phase 2, partial)
- **Session marking as `suspended`** — session is not explicitly marked suspended on ASK (the `suspended` field exists in SessionInfo but is never set to true on pause)
- **Idempotency prerequisite** — tools do not consistently use `IdempotencyKey` for re-execution safety

### Not implemented (Phase 3 — Granular approval)
- Pre-execution approval for individual tool steps
- Approval based on cost/sensitivity
- Multi-instance approval coordination

### Deviations
- No `WebhookChannel` implementation (only `EmailNotificationChannel`).
- `NotificationChannel` interface exists in `engine.core.port.human` but only email implementation exists.

## 3. Implementation Phases

### Phase 1 – Infrastructure (this document, implemented)

1. **Notification-channel port** (`engine.core.port.human.NotificationChannel`)
   - `send(ApprovalRequest, approvalUrl)`
   - Implementations: `EmailChannel` (SMTP), `WebhookChannel`
2. **Token-based approval links**
   - extend `pending_approval` with `token` (hashed), `expires_at`, `channel`
   - endpoint `GET/POST /api/approval/{token}` (token-authenticated)
3. **Durable ApprovalSource**
   - `DurableApprovalSource`: persists request, sends notification, polls DB
     with configurable timeout
   - replaces/augments `WebApprovalSource`
4. **Configuration**
   - `engine.approval.channel`, `engine.approval.email.*`,
     `engine.approval.timeout-hours`, `engine.approval.token-ttl-days`

### Phase 2 – Suspend & Resume (follows)

1. **Paused outcome**
   - `ToolOutcome.Paused(approvalId)` and `StepResult.Paused`
   - extend `PromptResult` with `Paused` outcome
2. **Session suspend**
   - on ASK: persist approval, mark session as `suspended`,
     tool call returns `Paused`, turn ends with `PromptResult.Paused`
   - HTTP response: `{status: "paused", approvalId, approvalUrl}`
3. **Resume**
   - endpoint `POST /api/session/{id}/resume` or `SessionWake.wake(session)`
     on approval
   - re-run the turn; tool call is executed again (IdempotencyKey
     deduplicates)
4. **Idempotency prerequisite**
   - tools must use `IdempotencyKey` (at-least-once → exactly-once on
     re-execution)

### Phase 3 – Granular approval

- Pre-execution approval for individual tool steps
- Approval based on cost/sensitivity
- Multi-instance approval coordination

## 4. Multi-Instance Note

Approval futures are process-local. With multi-instance (shared DB) the decision
must run through the DB (Phase 1 polling or Phase 2 suspend/resume).
The `session_claim` table exists, but is not yet used in `Engine` — that is
where the approach would hook in.

## 5. What already exists

- `pending_approval` table (persisted, status
  `PENDING/ORPHANED/DECIDED/EXPIRED`)
- `WebApprovalSource` (template for new channels)
- `PermissionEngine.reply(...)` (ONCE/ALWAYS/REJECT)
- Web UI endpoints for approval
- `ApprovalStore` (project-wide "always allow")
- `SessionWake` + `suspended` field
- `IdempotencyKey` per tool call

## 6. Open Decisions

1. Token transport: URL with token in query parameter or POST body?
2. Email template: HTML or plain text?
3. Timeout: how long may an approval stay open (7 days?)?
4. Should the agent receive a synthetic hint on `Paused`?
5. Are `ASK` rules for MCP tools generated automatically?
