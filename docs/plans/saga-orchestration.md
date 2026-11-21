# Plan: Saga Orchestration (Complete Saga)

**Status:** Draft · 2026-11-21  
**Depends on:** [`saga-undo-tools.md`](saga-undo-tools.md) (M1–M4, implemented)  
**Scope:** M5–M10 (automatic orchestration, durable compensation, coverage, API/UI)

---

## Kurzfassung (DE)

Der MVP (`saga-undo-tools.md`) liefert ein **manuelles Per-Call-Undo**:
`write → undo_write → delete`, `worktree_create → undo_worktree_create → worktree_remove`.
Was fehlt, ist die **Saga als Transaktion**: ein Scope (Turn/Workflow-Node), automatische
Rückwärts-Kompensation bei Fehlern, ein durablem Compensation-Zustand
(`PENDING/RUNNING/APPLIED/FAILED`), deterministische Idempotenz, Crash-Recovery,
Compaction-Sicherheit, Backup/Restore für Datei-Overwrites, eine Irreversible-Tool-Policy,
REST/UI und Metriken.

Dieser Plan beschreibt den vollständigen Ausbau in sechs Meilensteinen (M5–M10).

---

## 0. Goal & Scope

### 0.1 Goal

A **saga** is a long-running unit of work composed of forward tool calls and their
compensating actions. The engine must be able to:

1. **Define a scope** (turn, workflow node, explicit API request).
2. **Record** every forward call in that scope (durably, compaction-safe).
3. **Compensate automatically** in reverse order when the scope fails.
4. **Execute compensations durably**, with idempotency, claims, retries and recovery.
5. **Classify tools** as read-only, undoable, compensable or irreversible.
6. **Expose** manual and batch undo via API/UI.
7. **Audit and observe** every step.

### 0.2 Non-goals

- Distributed sagas across multiple engine instances/hosts (single engine instance owns a scope).
- Exactly-once guarantees for external side effects (at-least-once + idempotent compensators).
- Automatic compensation of arbitrary `shell` commands.
- Undo of already-sent emails or external irreversible actions.
- Time-travel/debugging of arbitrary historical states.

### 0.3 Design principles

- **Durable before execution**: a compensation intent is persisted before the compensator runs.
- **Idempotent by construction**: deterministic idempotency keys, CAS claims, leases.
- **Least privilege**: compensations run with a restricted system principal and a dedicated policy.
- **Fail visible**: partial compensation is reported, never silently swallowed.
- **Backward compatible**: manual `undo_<tool>` keeps working; scopes are additive.
- **Compaction-safe**: saga data lives outside the folded conversation.

---

## 1. Current State (M1–M4)

Implemented:

- `UndoSpec`, `UndoInputMapper`, `UndoGuard` in `core/contract/tool`.
- `UndoRegistry`, `UndoToolFactory`, `UndoRuntime`, `UndoRequest`, `UndoOutcome` in
  `runtime/tool/undo`.
- Engine wiring in `Engine.start()` / `Engine.reloadConfig()`.
- `write → delete` (creations only) and `worktree_create → worktree_remove`.
- Tests: `UndoRuntimeTest`, `WriteUndoIntegrationTest`, `WorktreeUndoIntegrationTest`.

Known gaps that this plan closes:

| Gap | Impact |
|---|---|
| No scope/orchestration | No automatic compensation, no batch undo |
| Audit written **after** execution | Crash between execution and audit can double-compensate |
| New `CallId`/`IdempotencyKey` per undo | Compensator cannot dedupe retries |
| `alreadyUndone` is read-then-write | Concurrent undos can double-apply |
| Conversation is the only source | `compactSessionToResult` deletes tool cards → undo impossible |
| `write` overwrite rejected | No restore of previous content |
| `edit`/`patch`/`delete` not undoable | Large uncovered surface |
| No safety classification | Irreversible actions can run inside a "saga" |
| No pre-flight | Forward call can succeed although the compensator is missing/denied |
| No REST/UI | Only the model can trigger undo |
| No metrics/recovery | Operational blind spot |

### 1.1 Important deviation from the MVP plan

The MVP plan assumed `ToolCalled`/`ToolSucceeded`/`ToolFailed` journal events. Those events
are **never written** in this codebase. M1–M4 therefore read the folded conversation
(`ToolCard`) and use the journal only for the `{undoOf}` audit marker.

This plan introduces a dedicated **`SagaStore`** as the authoritative source for saga
calls. `UndoRuntime` will read `SagaStore` first and fall back to the conversation for
legacy calls.

---

## 2. Definitions & Invariants

### 2.1 Terms

| Term | Meaning |
|---|---|
| **Saga scope** | A named, ordered unit of forward calls that must be compensated together |
| **Forward call** | One executed tool call (`ToolCard`, `CallId`) inside a scope |
| **Compensation** | The compensating tool call for one forward call |
| **Compensation record** | Durable state of one compensation (`PENDING/RUNNING/APPLIED/FAILED/…`) |
| **Saga plan** | Ordered list of compensations derived from a scope's calls |
| **Irreversible** | A tool whose effect cannot be undone (e.g. `email_send`, arbitrary `shell`) |

### 2.2 Invariants

- **I1 — Intent first**: a `CompensationRecord` exists in `PENDING` before the compensator runs.
- **I2 — Single owner**: at most one compensation may be `RUNNING` per forward call (CAS + lease).
- **I3 — Idempotent**: every compensation uses a deterministic idempotency key derived from the
  forward `CallId`; retries cannot double-apply.
- **I4 — Reverse order**: within a scope, compensations execute in descending `call_order`.
- **I5 — No running undo**: `Streaming`/`Running` forward calls are not compensable.
- **I6 — Permission-gated**: undo tool and compensating tool both pass the permission gate.
- **I7 — Append-only audit**: saga events are never updated in place; state changes are new events.
- **I8 — Compaction-safe**: saga call data is persisted independently of the conversation.
- **I9 — Irreversible policy**: in `STRICT` scopes, irreversible tools are blocked before execution.
- **I10 — Monotonic status**: scope/compensation statuses only move forward
  (`PENDING → RUNNING → APPLIED/FAILED`; `OPEN → COMMITTED/ABORTING → COMPENSATED/PARTIAL/FAILED`).

### 2.3 Optionality & Activation

Saga orchestration is **opt-in**. The manual MVP (`undo_<tool>`) must keep working with the
feature fully disabled, and a deployment must be able to enable recording/compensation per
agent, workflow node or request.

#### 2.3.1 Modes

`SagaPolicy` is the effective mode of a scope:

```java
public enum SagaPolicy {
    OFF,          // no scopes, no undo tools, no saga REST; zero overhead
    MANUAL,       // undo_<tool> generated; no automatic orchestration (current MVP)
    OBSERVE,      // scopes + calls recorded; no automatic compensation (dry-run/telemetry)
    BEST_EFFORT,  // automatic compensation; irreversible tools allowed but flagged
    STRICT        // automatic compensation; irreversible tools blocked before execution
}
```

`OFF` and `MANUAL` are **zero-orchestration** modes: no `SagaStore` writes and no saga
events. The only difference is that `MANUAL` exposes the per-call `undo_<tool>` tools.

#### 2.3.2 Resolution hierarchy

The effective mode for a prompt is resolved from the following levels:

| Level | Source | Scope |
|---|---|---|
| 1. Global | `engine.saga.policy` | deployment |
| 2. Agent | `AgentConfig.sagaPolicy` (`"inherit"` default) | agent |
| 3. Workflow node | `WorkflowDefinition.Node.sagaPolicy` (`"inherit"` default) | node |
| 4. Session | session metadata `saga.policy` (owner/admin) | session |
| 5. Request | prompt request `sagaPolicy` (admin only) | one prompt |

**Cap rule**: an override may only **lower** the mode, never raise it:

```java
SagaPolicy effective = mostRestrictive(global, agent, node, session, request);
// order: OFF < MANUAL < OBSERVE < BEST_EFFORT < STRICT
```

An agent config therefore cannot enable `STRICT` when the deployment is `MANUAL`; it can
only opt out. An admin can raise the global default, or use the explicit request override
when `engine.saga.allow-admin-override=true`.

Resolution is **fail-closed**: an unknown or malformed value resolves to `OFF` and logs a
warning.

#### 2.3.3 Behavior matrix

| Capability | OFF | MANUAL | OBSERVE | BEST_EFFORT | STRICT |
|---|---|---|---|---|---|
| `undo_<tool>` generated | no | yes | yes | yes | yes |
| Manual `undo_<tool>` (model) | no | yes | yes | yes | yes |
| Manual undo via REST | no (404) | yes | yes | yes | yes |
| Scope + `saga_call` recording | no | no | yes | yes | yes |
| Automatic compensation on failure | no | no | no | yes | yes |
| Irreversible tools blocked | no | no | no | flagged only | yes |
| File snapshots before write/edit/patch/delete | no | no | yes | yes | yes |
| Saga events / metrics | no | no | yes | yes | yes |
| Saga panel / scope undo | no | no | read-only | yes | yes |

#### 2.3.4 No-op wiring

When the effective mode is `OFF`:

- `Engine` wires `SagaStore.noop()`, `SagaCoordinator.disabled()`,
  `CompensationExecutor.disabled()`, `SagaRecovery.disabled()`.
- `Engine.registerUndoTools()` is skipped; no `undo_<tool>` is in the catalog.
- `QocEngine` does not create scopes or record calls.
- `ToolRuntime` receives `SagaGuard.noop()` (the STRICT guard is disabled).
- `ToolContext.sagaId` is `Option.none()`.
- No `SagaStore` rows and no saga `SessionEvent`s are written.
- `SagaResource` returns `404 saga_disabled` for all endpoints.

When the effective mode is `MANUAL`:

- `Engine.registerUndoTools()` runs (current behavior).
- `UndoRuntime` uses the conversation fallback (or `SagaStore` if `OBSERVE` was enabled
  earlier and rows exist).
- No scopes, no automatic compensation, no snapshots, no saga events.
- File tools keep the MVP behavior (metadata-only undo for `write` creations and
  `worktree_create`); they do **not** capture snapshots.

When the effective mode is `OBSERVE`:

- Scopes and `SagaCall` rows are written; `SagaCallRecorded` events are emitted.
- `SagaCoordinator.abort` marks the scope `ABORTING` but does **not** execute
  compensations; it transitions directly to `COMPENSATED` with reason `observe`.
- File tools capture snapshots only when the call belongs to an active scope
  (`ToolContext.sagaId` present).
- No compensator execution, no `ToolUndone` events.
- Useful to validate recording, compaction safety and timelines in production.

#### 2.3.5 Schema

The V13 migration is **additive** and always applied (Flyway). An `OFF`/`MANUAL`
deployment simply never writes to the tables. This avoids conditional migrations and keeps
the schema consistent across environments.

If a deployment forbids the tables entirely, keep `SagaStore.noop()` and exclude
`V13__saga.sql` via a separate Flyway location. This is documented but not the default.

#### 2.3.6 Rollout

1. **Phase 1** — `MANUAL` for all (current behavior, no schema writes).
2. **Phase 2** — `OBSERVE` for selected agents/workflows; collect saga timelines, no
   compensation. Validates recording and compaction safety.
3. **Phase 3** — `BEST_EFFORT` for low-risk workflows; irreversible tools flagged.
4. **Phase 4** — `STRICT` for critical workflows; pre-flight blocks irreversible tools.

Each phase is a config change (`engine.saga.policy`) or an agent/node override — no code
deploy.

#### 2.3.7 Reload & rollback

`Engine.reloadConfig()` re-resolves the global mode and swaps the coordinator. In-flight
scopes keep the coordinator/store they started with; new prompts use the new mode. If the
mode is lowered to `OFF` while a scope is `ABORTING`, the in-flight compensation completes
(it is already durable) and no new scopes are created.

Rollback from `BEST_EFFORT`/`STRICT` to `MANUAL`/`OFF` is a config change. Open scopes are
left for `SagaRecovery`; manual undo works for their calls via `SagaStore`.

---

## 3. Architecture Overview

```
┌────────────────────────────── Engine / QocEngine ──────────────────────────────┐
│  runPrompt(scope, …)                                                            │
│    ├─ SagaCoordinator.begin(kind, session, agent)                              │
│    ├─ ToolRuntime.execute(...)  ──► ToolCard persisted                         │
│    ├─ SagaCoordinator.recordCall(scope, call, outcome, card)                   │
│    ├─ success ──► SagaCoordinator.commit(scope)                                │
│    └─ failure ──► SagaCoordinator.abort(scope, error)                          │
│                         │                                                       │
│                         ▼                                                       │
│              SagaPlanBuilder (reverse order, skip non-undoable)                │
│                         │                                                       │
│                         ▼                                                       │
│              CompensationExecutor                                               │
│                ├─ claim (CAS + lease)                                           │
│                ├─ persist PENDING/STARTED                                       │
│                ├─ ToolRuntime.execute(compensator, derivedCtx, idemKey)         │
│                ├─ APPLIED / FAILED_RETRYABLE / FAILED_PERMANENT                │
│                └─ retry/backoff, timeout                                        │
│                         │                                                       │
│                         ▼                                                       │
│              SagaStore (scope, call, compensation, audit)                      │
└────────────────────────────────────────────────────────────────────────────────┘
```

New components:

| Component | Package | Responsibility |
|---|---|---|
| `SagaCoordinator` | `runtime.saga` | Scope lifecycle, recording, planning, abort/commit |
| `SagaPlanBuilder` | `runtime.saga` | Reverse-order plan, skip rules, irreversibility policy |
| `CompensationExecutor` | `runtime.saga` | Claim, execute, retry, audit one compensation |
| `SagaRecovery` | `runtime.saga` | Resume `PENDING`/expired `RUNNING` after restart |
| `SagaPermissionPolicy` | `runtime.saga` | System principal rules for compensations |
| `ToolSafetyRegistry` | `runtime.saga` | Classify tools (`READ_ONLY/UNDOABLE/COMPENSABLE/IRREVERSIBLE`) |
| `SagaStore` | `core.port.store` | Durable scope/call/compensation records |
| `SagaResource` | `quarkus…http` | REST API for manual/batch undo and saga status |
| `SagaPanel` / `UndoButton` | `reactflow` | UI |

---

## 4. Data Model

### 4.1 New IDs and enums

Files: `engine/src/main/java/de/augmentia/oc/engine/core/model/`

```java
public final class SagaId {
    // "sag_" + 32 random bytes (hex), same style as SessionId/CallId
    public static SagaId create();
    public static SagaId parse(String raw);
    public static SagaId of(String raw);
    public String value();
}

public final class CompensationId {
    // "cmp_" + 32 random bytes (hex)
    public static CompensationId create();
    public static CompensationId parse(String raw);
    public static CompensationId of(String raw);
    public String value();
}
```

```java
public enum SagaKind { TURN, WORKFLOW_NODE, MANUAL }

public enum SagaStatus {
    OPEN, COMMITTED, ABORTING, COMPENSATED, PARTIALLY_COMPENSATED,
    COMPENSATION_FAILED, AWAITING_APPROVAL
}

public enum CompensationStatus {
    PENDING, RUNNING, APPLIED, FAILED_RETRYABLE, FAILED_PERMANENT,
    SKIPPED, AWAITING_APPROVAL
}

public enum SagaPolicy { OFF, MANUAL, OBSERVE, BEST_EFFORT, STRICT } // see §2.3.1

public enum ToolSafety { READ_ONLY, UNDOABLE, COMPENSABLE, IRREVERSIBLE }
```

`ToolSafety` semantics:

- `READ_ONLY`: no side effect; never compensated, allowed in every scope.
- `UNDOABLE`: a registered `UndoSpec` exists and the compensator is available (file/tool state).
- `COMPENSABLE`: a compensating action exists, but the forward effect is external
  (e.g. deploy/undeploy). Undo is best-effort.
- `IRREVERSIBLE`: no compensator; in `STRICT` scopes the engine refuses to execute it.

### 4.2 Records

File: `core/model/SagaScope.java`

```java
public record SagaScope(
        SagaId id,
        SessionId sessionId,
        AgentId agentId,
        SagaKind kind,
        Option<SagaId> parentId,
        SagaStatus status,
        SagaPolicy policy,
        String reason,
        Instant startedAt,
        Option<Instant> endedAt
) {}
```

File: `core/model/SagaCall.java`

```java
public record SagaCall(
        CallId callId,
        SagaId scopeId,
        SessionId sessionId,
        AgentId agentId,
        MessageId messageId,
        ToolName toolName,
        JsonValue input,
        Option<JsonValue> output,
        Map<String, Object> metadata,
        ToolSafety safety,
        int order,
        Instant createdAt
) {}
```

File: `core/model/CompensationRecord.java`

```java
public record CompensationRecord(
        CompensationId id,
        SagaId scopeId,
        CallId callId,
        ToolName forwardTool,
        ToolName compensatingTool,
        CompensationStatus status,
        int attempts,
        Option<SessionError> lastError,
        Option<String> leaseOwner,
        Option<Instant> leaseUntil,
        Instant createdAt,
        Instant updatedAt
) {}
```

### 4.3 New session events

File: `core/model/SessionEvent.java` (add records; keep the sealed interface exhaustive).

```java
record SagaStarted(SagaId scopeId, SessionId sessionId, AgentId agentId,
                   SagaKind kind, Option<SagaId> parentId, SagaPolicy policy) {}
record SagaCallRecorded(SagaId scopeId, CallId callId, ToolName toolName,
                        ToolSafety safety, int order) {}
record SagaCommitted(SagaId scopeId) {}
record SagaAborted(SagaId scopeId, String reason) {}
record CompensationPlanned(SagaId scopeId, CompensationId compensationId,
                           CallId callId, ToolName compensatingTool) {}
record CompensationStarted(SagaId scopeId, CompensationId compensationId,
                           CallId callId, ToolName compensatingTool, int attempt) {}
record CompensationApplied(SagaId scopeId, CompensationId compensationId,
                           CallId callId, ToolName compensatingTool) {}
record CompensationFailed(SagaId scopeId, CompensationId compensationId,
                          CallId callId, ToolName compensatingTool,
                          SessionError error, int attempt) {}
record CompensationSkipped(SagaId scopeId, CompensationId compensationId,
                           CallId callId, String reason) {}
record SagaCompleted(SagaId scopeId, SagaStatus status) {}
record SagaAwaitingApproval(SagaId scopeId, CompensationId compensationId,
                            String approvalId) {}
record ToolUndone(SessionId sessionId, MessageId messageId,
                  CallId callId, CallId undoCallId, ToolName toolName,
                  CompensationId compensationId) {}
```

Register every new event in:

- `core/event/EventCodec.java` (`register(registry, …)`)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/NativeReflectionConfig.java`

### 4.4 SagaStore port

File: `core/port/store/SagaStore.java`

```java
public interface SagaStore {

    // scope
    void createScope(SagaScope scope);
    Option<SagaScope> scope(SagaId id);
    List<SagaScope> scopes(SessionId session, SagaStatus status, int limit);
    void updateScope(SagaScope scope);

    // calls
    void recordCall(SagaCall call);
    Option<SagaCall> call(SessionId session, CallId callId);
    List<SagaCall> calls(SagaId scopeId);

    // compensations
    void createCompensation(CompensationRecord record);
    Option<CompensationRecord> compensation(CompensationId id);
    List<CompensationRecord> compensations(SagaId scopeId);
    List<CompensationRecord> pendingCompensations(int limit);
    List<CompensationRecord> expiredLeases(Instant now, int limit);

    /** CAS: PENDING/FAILED_RETRYABLE/expired RUNNING -> RUNNING with lease. */
    Option<CompensationRecord> claim(CompensationId id, String owner, Instant leaseUntil);

    void updateCompensation(CompensationRecord record);

    // retention
    int deleteScopesOlderThan(Instant cutoff);
}
```

Add `SagaStore saga()` to `core/wiring/Stores.java` and update all constructors/call sites
(`InMemoryStores`, SQL wiring, test fixtures).

---

## 5. Persistence

### 5.1 SQL migration

File: `engine/src/main/resources/db/migration/V13__saga.sql`

```sql
CREATE TABLE saga_scope (
    id            VARCHAR(80) PRIMARY KEY,
    session_id    VARCHAR(160) NOT NULL,
    agent_id      VARCHAR(80)  NOT NULL,
    parent_id     VARCHAR(80),
    kind          VARCHAR(32)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    policy        VARCHAR(32)  NOT NULL,
    reason        TEXT,
    started_at    TIMESTAMP    NOT NULL,
    ended_at      TIMESTAMP
);
CREATE INDEX idx_saga_scope_session ON saga_scope(session_id, started_at DESC);
CREATE INDEX idx_saga_scope_status  ON saga_scope(status);

CREATE TABLE saga_call (
    call_id       VARCHAR(100) PRIMARY KEY,
    scope_id      VARCHAR(80)  NOT NULL REFERENCES saga_scope(id) ON DELETE CASCADE,
    session_id    VARCHAR(160) NOT NULL,
    agent_id      VARCHAR(80)  NOT NULL,
    message_id    VARCHAR(80)  NOT NULL,
    tool_name     VARCHAR(160) NOT NULL,
    input_json    TEXT         NOT NULL,
    output_json   TEXT,
    metadata_json TEXT         NOT NULL,
    safety        VARCHAR(32)  NOT NULL,
    call_order    INTEGER      NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);
CREATE INDEX idx_saga_call_scope ON saga_call(scope_id, call_order);
CREATE INDEX idx_saga_call_session ON saga_call(session_id);

CREATE TABLE saga_compensation (
    id                VARCHAR(80) PRIMARY KEY,
    scope_id          VARCHAR(80) NOT NULL REFERENCES saga_scope(id) ON DELETE CASCADE,
    call_id           VARCHAR(100) NOT NULL REFERENCES saga_call(call_id) ON DELETE CASCADE,
    forward_tool      VARCHAR(160) NOT NULL,
    compensating_tool VARCHAR(160) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    attempts          INTEGER     NOT NULL DEFAULT 0,
    last_error_json   TEXT,
    lease_owner       VARCHAR(160),
    lease_until       TIMESTAMP,
    created_at        TIMESTAMP   NOT NULL,
    updated_at        TIMESTAMP   NOT NULL
);
CREATE UNIQUE INDEX uq_saga_comp_call ON saga_compensation(call_id);
CREATE INDEX idx_saga_comp_status ON saga_compensation(status, lease_until);
```

Notes:

- `call_id` is unique across scopes (CallIds are generated per call).
- `uq_saga_comp_call` enforces **I2** at the database level: one compensation per forward call.
- JSON columns are `TEXT` for Postgres/SQLite portability; the adapter uses the existing
  JSON helpers.
- Retention: `SagaStore.deleteScopesOlderThan(cutoff)` called by `RetentionService`.

### 5.2 Adapters

- `adapter/memory/InMemorySagaStore` (tests, memory mode).
- `adapter/sql/AbstractJdbcSagaStore` + `PostgresSagaStore` / `SqliteSagaStore` (follow
  `AbstractJdbcConversation`).
- `Stores` wiring in `Engine.start()` and `InMemoryStores`.

### 5.3 Compaction safety (critical)

`QocEngine.compactSessionToResult` calls `conversation.deleteFrom(session, startSeq)`.
That deletes the `ToolCard`s. `UndoRuntime` must not depend on them.

Design:

1. **Record at execution time**: after `ToolRuntime.execute` returns and the `ToolCard` is
   persisted, `SagaCoordinator.recordCall` writes a `SagaCall` row. From then on, the call
   is undoable independent of the conversation.
2. **Undo lookup order**: `UndoRuntime` → `SagaStore.call(session, callId)` → conversation
   fallback (legacy sessions).
3. **Compaction**: `compactSessionToResult` must **not** delete rows from `saga_call`; it
   only deletes conversation messages. No extra hook is needed if step 1 is implemented.
4. **Legacy backfill**: on first undo of a legacy call (no `SagaCall`), import the
   `ToolCard` into `SagaStore` (best-effort) before compensating.

This also fixes the MVP's “journal events are never written” problem: the `SagaStore` is
the authoritative execution log for saga purposes.

### 5.4 Recovery

File: `runtime/saga/SagaRecovery.java`

```java
public final class SagaRecovery {
    /** Called once at Engine start, after stores are wired, before accepting prompts. */
    public void recover();
}
```

Algorithm:

1. Load `pendingCompensations(limit=1000)` and `expiredLeases(now, limit=1000)`.
2. For each record, determine the owning scope:
   - `ABORTING` scope → resume the compensation plan.
   - `OPEN` scope older than a threshold → mark `COMPENSATION_FAILED` (orphan) and alert.
3. Claim with a recovery owner (`"recovery:" + instanceId`), execute, update.
4. Emit `CompensationStarted` / `Applied` / `Failed`.
5. Recovery is idempotent; safe to run on every start.

Startup integration in `Engine.start()`:

```java
sagaRecovery.recover();
```

The recovery must run **after** `journal`, `conversation`, `toolRuntime` and `sagaStore` are
initialized but **before** the HTTP endpoints accept traffic. Use a `@PostConstruct`-style
hook or the existing startup sequence; do not block the event loop for long (bounded batch,
continue in the background if needed).

---

## 6. Orchestration

### 6.1 SagaCoordinator

File: `runtime/saga/SagaCoordinator.java`

```java
public final class SagaCoordinator {
    public SagaScope begin(SessionId session, AgentId agent, SagaKind kind,
                           Option<SagaId> parent, SagaPolicy policy);
    public void recordCall(SagaScope scope, ToolCall call, ToolOutcome outcome,
                           ToolCard card, ToolSafety safety);
    public void commit(SagaScope scope);
    public SagaOutcome abort(SagaScope scope, String reason, Principal principal);
    public SagaOutcome compensateCall(SagaCall call, String reason, Principal principal);
    public SagaOutcome compensateScope(SagaId scopeId, String reason, Principal principal);
}
```

`SagaOutcome`:

```java
public sealed interface SagaOutcome {
    record Committed(SagaId scopeId) implements SagaOutcome {}
    record Compensated(SagaId scopeId, int applied, int skipped) implements SagaOutcome {}
    record PartiallyCompensated(SagaId scopeId, int applied, int failed, int skipped,
                                List<CompensationFailure> failures) implements SagaOutcome {}
    record Failed(SagaId scopeId, int applied, int failed, List<CompensationFailure> failures)
            implements SagaOutcome {}
    record AwaitingApproval(SagaId scopeId, CompensationId compensationId, String approvalId)
            implements SagaOutcome {}
}
```

`begin`:

1. Create `SagaId`.
2. Persist `SagaScope(status=OPEN)`.
3. Emit `SagaStarted`.
4. Return the scope.

`recordCall`:

1. Compute `ToolSafety` from `ToolSafetyRegistry`.
2. Persist `SagaCall` with an increasing `order` (`select max(call_order)+1` or a per-scope
   counter in the coordinator).
3. Emit `SagaCallRecorded`.
4. If `safety == IRREVERSIBLE` and `policy == STRICT`, this should not happen (see pre-flight);
   otherwise mark it and continue in `BEST_EFFORT`.

`commit`:

1. `scope.status = COMMITTED`, `endedAt = now`.
2. Emit `SagaCommitted`.

`abort`:

1. `scope.status = ABORTING`, `reason = reason`.
2. Emit `SagaAborted`.
3. Build plan (`SagaPlanBuilder`).
4. For each compensation in reverse order: `CompensationExecutor.execute(record, principal)`.
5. Determine final status:
   - all `APPLIED`/`SKIPPED` → `COMPENSATED`
   - some failed, some applied → `PARTIALLY_COMPENSATED`
   - all failed → `COMPENSATION_FAILED`
   - one `AWAITING_APPROVAL` → `AWAITING_APPROVAL`
6. Update scope, emit `SagaCompleted` (or `SagaAwaitingApproval`).

### 6.2 Scope lifecycle in QocEngine

`QocEngine.runPrompt` currently executes steps and persists tool cards. Add an optional
`SagaContext` parameter (or extend `ExecutionOverrides`) so the caller decides the scope
kind and parent:

```java
public record SagaContext(SagaId scopeId, SessionId sessionId, AgentId agentId,
                          SagaKind kind, SagaPolicy policy) {}
```

Flow inside `runPrompt`:

```java
SagaScope scope = sagaContext != null
        ? sagaCoordinator.attach(sagaContext)
        : sagaCoordinator.begin(session, agent, SagaKind.TURN, Option.none(), sagaPolicy);

try {
    // existing step loop
    // after each ToolCallOutcome:
    sagaCoordinator.recordCall(scope, call, outcome, card, safety);
    // on success:
    sagaCoordinator.commit(scope);
} catch (Failure/Abort/…) {
    sagaCoordinator.abort(scope, reason, principal);
    throw;
}
```

Important: `runPrompt` returns `EngineResult.failed(...)` for expected failures, not always
by throwing. Both paths must abort the scope:

```java
if (result.failed()) {
    sagaCoordinator.abort(scope, result.errorMessage(), principal);
    return result;
}
```

For `paused()` results (approval), do **not** abort; keep the scope `OPEN` and resume it
when the approval is resolved. Persist the scope ID in the paused prompt/approval metadata.

### 6.3 Workflow integration

File: `workflow-runner/.../runner/DefaultNodeDispatcher.java`

Around the agent node (`runPrompt`):

```java
SagaScope nodeScope = sagaCoordinator.begin(
        runSession, agentId, SagaKind.WORKFLOW_NODE, parentScope, sagaPolicy);
try {
    EngineResult result = engine.runPrompt(..., new SagaContext(nodeScope.id(), ...));
    if (result.failed()) {
        sagaCoordinator.abort(nodeScope, result.errorMessage(), principal);
        throw new IllegalStateException(...);
    }
    sagaCoordinator.commit(nodeScope);
} catch (RuntimeException e) {
    sagaCoordinator.abort(nodeScope, e.getMessage(), principal);
    throw e;
}
```

Nested scopes:

- A `WORKFLOW_NODE` scope may contain a `TURN` child scope (from `runPrompt`).
- `abort(nodeScope)` compensates **children first**, then the node's own calls, then the
  parent (if the parent is also aborted). The coordinator walks `parentId` upwards only when
  the caller passes `cascade=true`.
- Default: `abort` compensates only the given scope and its descendants, not ancestors.

### 6.4 Parallel tool calls

`QocEngine` may execute multiple tool calls in one step. Compensation order must be safe.

Options:

1. **Record order by completion**, not by call start. Reverse completion order is the
   best approximation of causal order.
2. For known dependency groups (e.g. tools sharing the same `path`), execute compensations
   sequentially in reverse order; never in parallel.
3. Default compensation executor is **sequential**. Parallel compensation is a later
   optimization and must be explicitly enabled per scope.

### 6.5 Partial failure policy

The effective `SagaPolicy` (see §2.3) controls orchestration and failure handling:

- `OFF`: no scope, no undo tools, no automatic compensation.
- `MANUAL`: undo tools only; no automatic compensation. This is the default until M7.
- `OBSERVE`: scopes/calls are recorded; `abort` marks the scope `COMPENSATED` with reason
  `observe` and never executes a compensator.
- `BEST_EFFORT` (first automatic mode): compensate everything possible in reverse order;
  report failures; continue with the remaining compensations.
- `STRICT`: pre-flight blocks irreversible tools; any compensation failure marks the scope
  `COMPENSATION_FAILED` and alerts. Compensation continues for the remaining calls unless
  `engine.saga.strict.stop-on-failure=true`.

`SagaPlanBuilder` returns a plan with `CompensationPlan` entries:

```java
public record CompensationPlan(
        SagaCall call,
        ToolName compensatingTool,
        CompensationStatus initialStatus,
        Option<String> skipReason
) {}
```

Skip rules:

- `safety == READ_ONLY` → skip (`already no side effect`).
- `UndoSpec` absent → skip (`not compensable`) in `BEST_EFFORT`; in `STRICT`, pre-flight
  prevents this call from happening.
- `card.state` not `Completed` → skip (`original call did not complete`).
- Forward call is itself an undo/compensation call → skip (avoid recursion; see §7.7).
- Call already has an `APPLIED` compensation → skip (idempotent).

---

## 7. Compensation Execution

### 7.1 CompensationExecutor

File: `runtime/saga/CompensationExecutor.java`

```java
public final class CompensationExecutor {
    public CompensationResult execute(CompensationRecord pending, Principal principal, String reason);
}
```

`CompensationResult`:

```java
public sealed interface CompensationResult {
    record Applied(CompensationId id, CallId callId) implements CompensationResult {}
    record Skipped(CompensationId id, String reason) implements CompensationResult {}
    record Failed(CompensationId id, SessionError error, boolean retryable) implements CompensationResult {}
    record AwaitingApproval(CompensationId id, String approvalId) implements CompensationResult {}
}
```

Algorithm:

```
1. claim = sagaStore.claim(id, owner, now + lease)      // CAS
   if empty -> return Skipped("not claimable")
2. emit CompensationStarted(attempt = claim.attempts + 1)
3. resolve forward call from sagaStore.call(...)
4. resolve UndoSpec from UndoRegistry (or ToolSafetyRegistry)
5. build undoInput = spec.mapper().map(input, output, metadata)
6. derive ToolContext:
     sessionId = forward.sessionId
     agentId   = forward.agentId
     messageId = forward.messageId
     callId    = CallId.create()
     idemKey   = new IdempotencyKey("undo_" + forward.callId.value())
     principal = systemSagaPrincipal(principal)
7. execute compensating tool via ToolRuntime.execute(call, ctx)
   - ToolOutcome.Ok -> APPLIED, emit CompensationApplied + ToolUndone
   - ToolOutcome.Failed -> retryable? -> FAILED_RETRYABLE (attempts < max), else FAILED_PERMANENT
   - ToolOutcome.Paused -> AWAITING_APPROVAL (manual path only; automatic path pre-flighted)
8. update CompensationRecord; emit CompensationFailed on failure
```

### 7.2 Deterministic idempotency

Forward call: `IdempotencyKey.of(session, callId)` = `idem_<session>_<callId>`.  
Undo call: `new IdempotencyKey("idem_undo_" + session.value() + "_" + callId.value())`.

Why: stable across retries, distinct from the forward key, and usable by compensators that
dedupe side effects. Document this in `UndoRuntime` and `CompensationExecutor`.

### 7.3 Claim / CAS / lease

`SagaStore.claim` must be a single atomic operation:

```sql
UPDATE saga_compensation
SET status = 'RUNNING', lease_owner = ?, lease_until = ?, updated_at = ?
WHERE id = ?
  AND (status IN ('PENDING','FAILED_RETRYABLE')
       OR (status = 'RUNNING' AND lease_until < ?))
```

If `rows == 1`, load the row and return it; otherwise `Option.none()`.

Lease duration: 2× the tool timeout (default 60 s → 120 s). Recovery can take over expired
leases. This prevents two executors from running the same compensation.

### 7.4 Retry & backoff

Per compensation:

- `maxAttempts = 3` (configurable `engine.saga.compensation.max-attempts`).
- Backoff: 1 s, 5 s, 25 s (configurable).
- Retry only for retryable errors:
  - `ToolOutcome.Failed` with a transient `SessionError` (provider/transport/timeout).
  - `Unavailable` / `InvalidArgument`? `InvalidArgument` is permanent; `Unavailable` is retryable.
- Permanent errors: `permission_denied`, `compensating_tool_missing`, `mapper_error`,
  `guard_rejected`, `not_applicable`.
- After max attempts: `FAILED_PERMANENT`, emit `CompensationFailed`, continue per policy.

### 7.5 Permission & ASK policy

- Manual undo (`undo_<tool>` called by the model): unchanged; `ASK` may pause the outer call.
- Automatic compensation: use `Principal.system()` plus a dedicated `SagaPermissionPolicy`:
  - `ALLOW` the `undo_<tool>` action and the compensating tool's action.
  - Never `ASK`; if the policy would `ASK`, the compensation is `SKIPPED` with
    `awaiting_approval` and the scope becomes `AWAITING_APPROVAL`.
- Pre-flight (at scope begin, `STRICT`): for each undoable call, verify the compensator is
  registered and the agent would be allowed to execute it (system policy). If not, the
  forward call is blocked before execution.
- `BEST_EFFORT`: log a warning and mark the call `COMPENSABLE` but not guaranteed.

### 7.6 Timeout & cancellation

- Each compensation has its own timeout (the compensating tool's `ToolRuntime` timeout).
- The whole scope has a deadline `engine.saga.timeout` (default 5 min).
- On scope timeout: stop scheduling new compensations, mark remaining `FAILED_PERMANENT`
  with `saga_timeout`, emit `SagaCompleted(COMPENSATION_FAILED)`.
- Interrupted sessions: compensation is a separate execution, not tied to the model's
  virtual thread; a cancellation of the forward prompt must not cancel an in-flight
  compensation abruptly. Use a detached executor with a bounded pool.

### 7.7 Recursion guard

Never compensate a call to a tool that is itself an undo/compensation tool:

- Tool name starts with `undo_` → skip.
- Call is part of a `CompensationRecord` (`ToolUndone` / `CompensationApplied`) → skip.

This prevents `undo_undo_write` chains.

### 7.8 Audit

After every state transition, append the corresponding `SessionEvent` to the journal
(append-only). The `SagaStore` row is the queryable projection; the journal is the audit
log. Both are written in the same transaction if the adapter supports it; otherwise the
journal write is best-effort with a warning (but the `SagaStore` state is authoritative).

---

## 8. Tool Coverage & Classification

### 8.1 ToolSafetyRegistry

File: `runtime/saga/ToolSafetyRegistry.java`

```java
public final class ToolSafetyRegistry {
    public ToolSafety safetyOf(ToolDefinition definition);
}
```

Rules:

1. Explicit `ToolOptions.sagaSafety` wins.
2. `UndoSpec` present + compensator registered → `UNDOABLE`.
3. `UndoSpec` present + compensator missing → `COMPENSABLE` (with warning) in `BEST_EFFORT`;
   `IRREVERSIBLE` in `STRICT`.
4. Read-only tool names (`read`, `glob`, `grep`, `websearch`, `webfetch`, `skill`,
   `skill_list`, `question`, `todo`, `submit_result`, `list_mcp_resources`,
   `read_mcp_resource`, …) → `READ_ONLY`.
5. Everything else → `IRREVERSIBLE` (safe default).

Add `ToolOptions.sagaSafety`:

```java
public record ToolOptions(..., Option<UndoSpec> undo, ToolSafety sagaSafety) {
    // backward-compatible constructors delegate with sagaSafety = null (derive)
    public ToolOptions withSagaSafety(ToolSafety safety) { ... }
}
```

`ToolDefinition` gets a convenience method:

```java
public ToolDefinition withSagaSafety(ToolSafety safety) { ... }
```

### 8.2 Per-tool matrix

| Tool | Safety | Compensator | Notes |
|---|---|---|---|
| `read`, `glob`, `grep`, `websearch`, `webfetch` | `READ_ONLY` | — | no side effect |
| `skill`, `skill_list`, `question`, `todo`, `submit_result` | `READ_ONLY` | — | session-local/no external effect |
| `write` | `UNDOABLE` | `file_restore` | covers create and overwrite via snapshot |
| `edit` | `UNDOABLE` | `file_restore` | snapshot before edit |
| `patch` | `UNDOABLE` | `file_restore` | snapshot before patch |
| `delete` | `UNDOABLE` | `file_restore` | soft-delete/snapshot before delete |
| `worktree_create` | `UNDOABLE` | `worktree_remove` | existing |
| `worktree_remove` | `IRREVERSIBLE` | — | removal is destructive; add a `worktree_restore` later |
| `shell` | `IRREVERSIBLE` | — | arbitrary commands; `STRICT` blocks |
| `process` | `IRREVERSIBLE` | — | process lifecycle |
| `email_send` | `IRREVERSIBLE` | — | cannot unsend |
| `agent_create` | `IRREVERSIBLE` | — | add `agent_delete` compensator later |
| `agent_list`, `models`, `session_rename`? | `READ_ONLY` / `COMPENSABLE` | — | `session_rename` can be compensated by renaming back |
| `session_move` | `COMPENSABLE` | `session_move` | previous location |
| `session_rename` | `COMPENSABLE` | `session_rename` | previous title |
| `secure_rest` | `IRREVERSIBLE` | — | external side effects |
| `email_*` (read) | `READ_ONLY` | — | |
| `a2a_*` | `IRREVERSIBLE` | — | remote agent’s side effects |
| MCP tools | `IRREVERSIBLE` by default | manifest convention | see §8.5 |
| `undo_*` | `READ_ONLY` | — | recursion guard |
| `deploy` (example) | `COMPENSABLE` | `undeploy` | external system |

### 8.3 File backup and `file_restore`

New tool: `runtime/tool/fs/FileRestoreTool.java`

```java
/** Restores files from a SnapshotStore snapshot; deletes files that did not exist. */
public static ToolDefinition definition(FileSystem fs, FileAccess access,
                                        SnapshotStore snapshots, PermissionGate gate);
```

Input:

```json
{ "snapshotId": "snp_…", "path": "/abs/path", "existed": true }
```

Behaviour:

- `existed == true`: `snapshots.restore(SnapshotId.of(snapshotId), List.of(Path.of(path)))`.
- `existed == false`: `fileSystem.delete(Path.of(path))` (the file was created by the forward call).
- Verify the path is under the workspace (`FileAccess`).
- Permission action: `edit` (same as write/edit/patch/delete).

`write`/`edit`/`patch`/`delete` capture a snapshot **before** mutating:

```java
SnapshotId snapshot = snapshots.capture(List.of(resolved.absolute()));
Map<String, Object> metadata = Map.of(
        "path", resolved.absolute().toString(),
        "existed", existed,
        "snapshotId", snapshot.value());
```

Undo spec (shared helper `FileUndoSpecs.of()`):

```java
new UndoSpec(new ToolName("file_restore"),
        (originalInput, output, metadata) -> JsonValue.of(Map.of(
                "snapshotId", JsonValue.of(String.valueOf(metadata.get("snapshotId"))),
                "path", JsonValue.of(String.valueOf(metadata.get("path"))),
                "existed", JsonValue.of(Boolean.TRUE.equals(metadata.get("existed"))))),
        (originalInput, output, metadata) -> metadata.containsKey("snapshotId"));
```

For `delete`:

- Before deleting, capture a snapshot (or move to trash).
- `existed=true`, `snapshotId` set; `file_restore` restores the file.

SnapshotStore caveats:

- `NoOpSnapshotStore` (memory mode) must be replaced by an `InMemorySnapshotStore` that
  actually stores file bytes for tests and for memory deployments.
- Snapshot retention: `RetentionService` must keep snapshots referenced by `saga_call`
  metadata for at least the saga retention window. Add `snapshotId` to `saga_call` columns
  or parse metadata; simplest: add `snapshot_id VARCHAR(80)` to `saga_call`.
- Snapshot size limit: `engine.saga.backup.max-bytes` (default 5 MiB per file); if exceeded,
  mark the call `IRREVERSIBLE` and include a warning in the tool result (`”undo unavailable:
  backup exceeds limit”`) so the model/human knows.

### 8.4 Non-file tools

- `session_rename`: capture previous title in metadata; compensator `session_rename`.
- `session_move`: capture previous directory; compensator `session_move`.
- `worktree_remove`: add `worktree_restore` (recreate from branch/path) or mark irreversible.
- `agent_create`: add `agent_delete` (delete by id) or mark irreversible.
- `todo`: session-local; `READ_ONLY` for saga purposes.
- `submit_result`: terminal; `READ_ONLY`.

### 8.5 MCP / A2A convention

MCP tools cannot be changed at the source. Provide a manifest convention:

```json
{
  "tool": "deploy",
  "undo": { "tool": "undeploy", "input": { "service": "$input.service" } }
}
```

The MCP adapter (`McpRegistry`) reads an optional `undo` block from the server manifest and
attaches an `UndoSpec` to the `ToolDefinition`. Until then, all MCP/A2A tools are
`IRREVERSIBLE` by default.

---

## 9. Pre-flight & Registration

### 9.1 Registration validation

File: `runtime/saga/SagaToolValidator.java`

At `Engine.start()` / `reloadConfig()`, after all tools are registered:

1. For each definition with `UndoSpec`, resolve the compensating tool by effective name.
2. If missing: log a warning; mark `ToolSafety.COMPENSABLE` (or fail fast in `STRICT`).
3. If present: verify the compensating tool’s input schema accepts the mapper’s output
   (best-effort: run `Json.decode` on a sample? Not possible generically; document the
   contract and test per tool).
4. Store the resolved safety in `ToolSafetyRegistry`.

### 9.2 Scope pre-flight

`SagaCoordinator.begin(..., policy=STRICT)`:

1. Compute the set of tools the agent may use (`ToolSnapshot`).
2. For each potentially irreversible tool, check `ToolSafety`.
3. If the scope might execute an irreversible tool, fail the prompt **before** the first
   call with a clear error:
   `"saga: tool 'shell' is irreversible; use BEST_EFFORT or approve explicitly"`.
4. In `BEST_EFFORT`, proceed and mark the scope as partially compensable.

Because the tool call isn’t known until the model emits it, the practical check is:

- At `recordCall` time, if `safety == IRREVERSIBLE` and `policy == STRICT`, abort the scope
  and return a model-facing error **before** executing the tool. This requires a pre-execution
  hook in `ToolRuntime` (see §9.3).

### 9.3 Pre-execution hook

Add a `SagaGuard` hook to `Hooks` (or a separate `ToolGate`):

```java
public interface SagaGuard {
    /** @throws PermissionDenied/Abort when the tool is not allowed in this scope. */
    void check(ToolContext ctx, ToolDefinition definition, ToolCall call);
}
```

`ToolRuntime.execute` calls `sagaGuard.check(...)` before `permission.assertAllowed(...)`.
The guard inspects the active `SagaContext` (passed via `ToolContext` metadata or a
`SagaContext` field) and the tool safety.

`ToolContext` can carry the active scope ID:

```java
public record ToolContext(..., Option<SagaId> sagaId) { ... }
```

Backward-compatible constructor delegates with `Option.none()`.

---

## 10. Engine Wiring

File: `quarkus/.../http/Engine.java`

New fields:

```java
private SagaStore sagaStore;
private SagaCoordinator sagaCoordinator;
private CompensationExecutor compensationExecutor;
private SagaRecovery sagaRecovery;
private ToolSafetyRegistry toolSafetyRegistry;
```

`start()` order:

1. Stores (`sagaStore`).
2. Tool registry (existing).
3. `UndoRegistry.rebuildFrom(registry)`.
4. `ToolSafetyRegistry` (validates compensators).
5. Resolve the global `engine.saga.policy` (default `manual`).
6. Wire the mode-specific components (see below).
7. `registerUndoTools()` only when the mode is `MANUAL` or higher.
8. `toolSnapshot`, `toolRuntime`, `toolRuntimeRef.set(...)`.
9. `sagaRecovery.recover()` only when the mode is `OBSERVE` or higher and there may be
   pending/expired records.
10. HTTP ready.

Mode-based wiring (see §2.3):

| Effective mode | `sagaStore` | `sagaCoordinator` | `registerUndoTools()` | `sagaRecovery` |
|---|---|---|---|---|
| `OFF` | `SagaStore.noop()` | `SagaCoordinator.disabled()` | no | `SagaRecovery.disabled()` |
| `MANUAL` | `SagaStore.noop()` (or real if previously used) | `disabled()` | yes | `disabled()` |
| `OBSERVE` | real | recording; `abort` without compensation | yes | disabled |
| `BEST_EFFORT` | real | full | yes | enabled |
| `STRICT` | real | full + pre-flight guard | yes | enabled |

The **effective** mode per prompt is resolved from global/agent/node/session/request and
attached to the prompt via `SagaContext` (see §2.3.2). `Engine` only owns the global cap and
the shared components; `QocEngine` and `DefaultNodeDispatcher` decide whether to create a
scope.

`reloadConfig()`:

1. Re-resolve the global policy.
2. Rebuild `UndoRegistry`.
3. Rebuild `ToolSafetyRegistry`.
4. Swap the mode-specific coordinator/store wiring.
5. `registerUndoTools()` (idempotent) only for `MANUAL` or higher.
6. Do **not** cancel in-flight sagas; they use the existing store/runtime.
7. If the mode was lowered to `OFF`, in-flight `ABORTING` scopes still complete; no new
   scopes are created.

Subagents:

- Each subagent session gets its own `SagaScope(kind=TURN, parent=parentScope)`.
- On subagent failure, abort the subagent scope, then optionally the parent (policy).
- The parent’s `SagaScope` ID is propagated via `SagaContext`.

Ephemeral/workspace sessions:

- The current MVP filters undo tools from `buildEphemeralBaseSnapshot`.
- A complete saga needs a per-workspace `SagaCoordinator` whose `SnapshotStore` and
  `FileAccess` point at the ephemeral workspace. Either:
  - (a) build an ephemeral `SagaCoordinator` with the ephemeral tool runtime, or
  - (b) keep undo tools hidden in ephemeral sessions (documented limitation).
- Recommended: (a) for file tools; (b) for MCP/external tools.

---

## 11. API

File: `quarkus/.../http/SagaResource.java`

```java
@Path("/api/session/{sessionId}/saga")
@Produces(MediaType.APPLICATION_JSON)
public class SagaResource {

    @GET
    public Response list(@PathParam("sessionId") String sessionId,
                         @QueryParam("status") String status,
                         @QueryParam("limit") @DefaultValue("50") int limit);

    @GET
    @Path("/{scopeId}")
    public Response scope(@PathParam("sessionId") String sessionId,
                          @PathParam("scopeId") String scopeId);

    @POST
    @Path("/undo/{callId}")
    public Response undoCall(@PathParam("sessionId") String sessionId,
                             @PathParam("callId") String callId,
                             @HeaderParam("Idempotency-Key") String idempotencyKey);

    @POST
    @Path("/undo")
    public Response undoBatch(@PathParam("sessionId") String sessionId,
                              UndoBatchRequest request);

    @POST
    @Path("/{scopeId}/abort")
    public Response abort(@PathParam("sessionId") String sessionId,
                          @PathParam("scopeId") String scopeId,
                          AbortRequest request);
}
```

DTOs:

```java
public record SagaScopeDto(String id, String sessionId, String agentId, String kind,
                           String parentId, String status, String policy,
                           String startedAt, String endedAt,
                           List<SagaCallDto> calls,
                           List<CompensationDto> compensations) {}

public record SagaCallDto(String callId, String tool, boolean undoable,
                          String safety, int order, String createdAt) {}

public record CompensationDto(String id, String callId, String tool, String status,
                              int attempts, String lastError) {}

public record UndoBatchRequest(List<String> callIds, String reason) {}

public record AbortRequest(String reason) {}
```

Errors:

- `400` invalid ID / batch too large.
- `403` missing `saga:undo` permission or cross-session ownership.
- `404` session/call/scope not found.
- `409` compensation not claimable (already running/undone).
- `202` `AWAITING_APPROVAL` with `approvalId`.

Permissions:

- `saga:read` (GET) — resource = session ID.
- `saga:undo` (single undo) — resource = session ID.
- `saga:undo:batch` (batch) — resource = session ID; max `engine.saga.batch.max` (default 50).
- `saga:abort` (abort scope) — resource = session ID.

Idempotency:

- `Idempotency-Key` header is hashed into the compensation claim; a repeated request returns
  the existing result (`200`/`409`) instead of double-applying.
- If absent, derive from `sessionId + callId + reason`.

---

## 12. UI

File: `reactflow/src/features/chat/…`

### 12.1 Tool card

Add undo state to the tool card (from the message/saga API):

- `undoable` → button “Undo” (trash icon) with tooltip “Undo this call”.
- `undone` → badge “Undone” with timestamp.
- `not_undoable` → no button; tooltip “This action cannot be undone”.
- `irreversible` → warning icon; tooltip “Irreversible action”.
- `failed` → “Retry undo” if `failed_retryable`.

On click:

1. Confirmation dialog: “Undo `<tool>`? This will execute `<compensator>`.”
2. `POST /api/session/{id}/saga/undo/{callId}`.
3. Toast with result; refresh the message/saga state.

### 12.2 Turn undo

At the end of an assistant turn, show “Undo this turn” if the turn has undoable calls:

1. Fetch the turn’s scope (or use the message metadata `sagaId`).
2. `POST /api/session/{id}/saga/{scopeId}/abort` with reason `user_undo_turn`.
3. Show progress (compensations may take seconds).
4. Show a result summary: applied/skipped/failed.

### 12.3 Saga panel

A collapsible panel (or a modal) per session:

- Scope list with status (`OPEN`, `COMPENSATED`, `PARTIALLY_COMPENSATED`, `FAILED`).
- Call list in forward order; compensation status in reverse order.
- Failed compensations with error and “Retry”.
- “Abort scope” button (admin/manual).

### 12.4 Polling / live updates

- Use the existing event stream (SSE/WebSocket) or poll `GET /saga/{scopeId}` every 2 s while
  a compensation is running.
- Disable the button while `RUNNING`.

### 12.5 Mobile

- The tool card action must fit the mobile fixed inputbar; use a compact icon button with
  `touch-action: manipulation` and safe-area padding (same pattern as the Send/Cancel fix).

---

## 13. Security & Permissions

### 13.1 System principal

Automatic compensation uses:

```java
Principal.system() // id=system, role=system
```

plus a `SagaPermissionPolicy` that only allows:

- `undo_<forward>` for the specific call.
- the compensating tool’s action (e.g. `edit`, `worktree`).

It must **not** allow arbitrary tools, shell, or external MCP calls. The policy is a
`List<Rule>` built per compensation:

```java
List.of(
    new Rule("undo_" + forwardTool, "*", Effect.ALLOW),
    new Rule(compensatingAction, "*", Effect.ALLOW),
    new Rule("*", "*", Effect.DENY)
);
```

The permission engine is invoked with this list and the `Principal.system()`; `ASK` is
treated as `AWAITING_APPROVAL` (never auto-approved).

### 13.2 Ownership

`SagaStore.call(session, callId)` returns the call only for the requested session. For
cross-session undo:

- Allow only if the target session is the **current** session, a **child** of it, or the
  caller is `admin`.
- Check `SessionReader`/`SessionStore` ownership (`owner` column, V9 migration).
- Add `SagaAccessGuard` in `SagaResource` and `UndoRuntime`.

### 13.3 Batch limits

- Max calls per batch: `engine.saga.batch.max` (default 50).
- Only calls in the same session and not already `APPLIED`.
- Require `saga:undo:batch` permission.
- Audit the batch as one `SagaAborted`/`SagaCompleted` pair.

### 13.4 Irreversible actions

- `STRICT`: block before execution (pre-execution hook).
- `BEST_EFFORT`: allow, but the tool result includes `"saga": "irreversible"` and the UI
  shows a warning.
- Optional `engine.saga.irreversible=deny|ask|allow` (default `ask` in interactive mode).
- Human approval is stored in `ApprovalStore` and the scope becomes `AWAITING_APPROVAL`.

### 13.5 Secrets

- Never store `Principal.accessToken` or tool inputs containing secrets in the saga tables.
  Apply the existing redaction rules (`Hooks` / `LlmTrace`) before persisting `input_json`
  and `metadata_json`.
- Add a `SagaRedactor` that removes keys matching `(?i)(token|secret|password|api[_-]?key)`
  from persisted JSON.

---

## 14. Observability

### 14.1 Metrics (Micrometer)

- `qoc.saga.scopes.started{kind}`
- `qoc.saga.scopes.completed{status}`
- `qoc.saga.compensations{status}` (counter)
- `qoc.saga.compensation.duration` (timer)
- `qoc.saga.compensations.pending` (gauge)
- `qoc.saga.compensations.failed` (counter)
- `qoc.saga.recovery.claimed` (counter)

### 14.2 Logs

Structured fields: `sagaId`, `scopeKind`, `callId`, `compensationId`, `tool`, `attempt`,
`status`, `durationMs`, `reason`.

Log levels:

- `INFO`: scope started/committed/compensated.
- `WARN`: compensation failed/retry, missing compensator, irreversible in BEST_EFFORT.
- `ERROR`: scope `COMPENSATION_FAILED`, recovery orphan.

### 14.3 Events

All events in §4.3 are appended to the journal and available via the session event stream.
The UI can reconstruct the saga timeline from them.

### 14.4 Trace endpoint

`GET /api/session/{sessionId}/saga/{scopeId}` returns the full timeline (calls,
compensations, errors). This is the primary debugging surface.

---

## 15. Migration & Compatibility

### 15.1 Database

- `V13__saga.sql` (new tables, indexes).
- No change to existing tables.
- Backfill: none required. `SagaStore` starts empty; legacy calls are resolved from the
  conversation on demand and imported lazily.

### 15.2 API/tool compatibility

- `ToolOptions` gains `sagaSafety` as the **8th** component; all existing constructors are
  preserved (default derived).
- `ToolContext` gains `Option<SagaId> sagaId`; all existing constructors are preserved.
- `Stores` gains `SagaStore`; update `InMemoryStores` and SQL wiring.
- `UndoRuntime` constructor gains `SagaStore`; keep a backward-compatible constructor that
  uses a `NoOpSagaStore` for tests/embedding.
- `EngineConfig.AgentConfig` gains `sagaPolicy` (default `"inherit"`); add a backward-compatible
  constructor that delegates with `"inherit"`.
- `AgentProfile` gains `SagaPolicy sagaPolicy` (resolved per prompt).
- `WorkflowDefinition.Node` gains `sagaPolicy` (default `"inherit"`); add a backward-compatible
  constructor.
- `QocEngine.runPrompt` gains an optional `SagaContext` parameter; keep an overload that
  delegates with `SagaContext.none()`.
- `ExecutionOverrides` gains `SagaContext` (or `SagaPolicy`); existing constructors delegate
  with `none`.

### 15.3 Feature flags

Global:

- `engine.saga.policy` — `off|manual|observe|best-effort|strict` (default `manual`).
  This is the **cap**; agent/node/session/request overrides can only lower it.
- `engine.saga.allow-admin-override` (default `false`) — allows an admin request/session
  override to raise the mode above the global cap.
- `engine.saga.compensation.max-attempts` (3).
- `engine.saga.compensation.lease-seconds` (120).
- `engine.saga.compensation.backoff` (`1s,5s,25s`).
- `engine.saga.timeout-seconds` (300).
- `engine.saga.batch.max` (50).
- `engine.saga.backup.max-bytes` (5 MiB).
- `engine.saga.irreversible` (`deny|ask|allow`, default `ask`).
- `engine.saga.strict.stop-on-failure` (default `false`).
- `engine.saga.retention-days` (30).

Per-agent / per-node:

- `AgentConfig.sagaPolicy` (`inherit` default) in `EngineConfig`/agent JSON.
- `WorkflowDefinition.Node.sagaPolicy` (`inherit` default) in workflow JSON.
- Session metadata key `saga.policy` (owner/admin only).

Backward compatibility:

- If `engine.saga.enabled` is present and `engine.saga.policy` is absent, map:
  `false → manual`, `true → best-effort`. This flag is not yet released; the mapping is
  only a safety net.
- `engine.saga.manual-undo-tools` (default `true`) can disable `undo_<tool>` generation even
  in `MANUAL` (useful for a “no undo tools” rollout).

### 15.4 Context agents / compaction

- `compactSessionToResult` no longer affects undo because `saga_call` is independent.
- For context agents, the workflow node scope is the parent; compensation for a failed
  node includes the context agent’s calls (which are recorded in `saga_call` even though
  they are removed from the conversation).
- Add a test: write a file in a context-agent run, compact, then undo via `callId`.

---

## 16. Test Strategy

### 16.1 Unit tests

- `SagaId` / `CompensationId` validation.
- `SagaStore` in-memory: create/record/claim/lease/expiry.
- `SagaPlanBuilder`: reverse order, skip rules, recursion guard.
- `CompensationExecutor`: state transitions, retry, permanent vs retryable, idempotency.
- `ToolSafetyRegistry`: explicit safety, UndoSpec presence, missing compensator, defaults.
- `SagaPermissionPolicy`: only undo/compensator allowed; no shell.
- `SagaRedactor`: secrets removed from persisted JSON.
- `SagaPolicy` resolver:
  - global → agent → node → session → request precedence,
  - cap rule (override can only lower),
  - unknown/malformed value → `OFF`,
  - `allow-admin-override` behavior,
  - `enabled` legacy mapping.

### 16.2 Integration tests (engine, no Quarkus)

- `OFF`: no `undo_<tool>` in the snapshot, no `SagaStore` rows, no saga events,
  `SagaResource` 404, prompt completes normally.
- `MANUAL`: `undo_<tool>` present, manual undo works, no scope/recording, no automatic
  compensation.
- `OBSERVE`: scope + calls recorded, `SagaCallRecorded` emitted, `abort` marks
  `COMPENSATED` with reason `observe`, no compensator executed, no `ToolUndone`.
- `QocEngine` turn failure (`BEST_EFFORT`) → scope aborted → compensations applied in
  reverse order.
- `QocEngine` success → scope committed, no compensation.
- Workflow node failure → node scope compensated; parent untouched.
- Nested scope: node + turn; child compensated first.
- Compaction: record calls, `compactSessionToResult`, then undo succeeds from `SagaStore`.
- Legacy fallback: no `SagaCall`, undo reads the conversation.
- Pre-flight `STRICT`: irreversible tool is blocked before execution.
- `BEST_EFFORT`: irreversible tool runs, scope marked partially compensable.
- `ASK` during automatic compensation → `AWAITING_APPROVAL`, no auto-approval.
- Cross-session ownership denied.
- Batch limit and permissions.
- Reload lowering `STRICT → OFF`: in-flight compensation completes, no new scopes.

### 16.3 Concurrency tests

- Two threads call `undo` for the same call: exactly one claims; the other gets
  `AlreadyUndone`/`409`.
- Expired lease takeover: recovery claims and applies once.
- Parallel tool calls: compensation order is reverse completion order.

### 16.4 Crash/chaos tests

- Kill between `CompensationStarted` and the tool result; restart; recovery applies once.
- Kill after the tool result but before `CompensationApplied`; retry is idempotent
  (compensator with a deterministic key).
- Broken journal: `SagaStore` remains authoritative; audit warning is logged.
- Compensator timeout: retry/backoff, then `FAILED_PERMANENT`.

### 16.5 File backup tests

- `write` create → undo deletes.
- `write` overwrite → undo restores previous content.
- `edit`/`patch` → undo restores pre-edit content.
- `delete` → undo restores the file.
- Backup size limit → tool result marks undo unavailable.
- `SnapshotStore` memory adapter stores/restores bytes.

### 16.6 UI/E2E

- Playwright: tool card shows Undo; click → confirmation → file restored; badge “Undone”.
- Turn undo: multiple calls compensated in reverse order.
- Failure case: compensator fails → error toast + retry.
- Mobile viewport: button usable, no layout break.

### 16.7 Coverage

- JaCoCo instructions ≥ 80% (existing gate).
- New saga package ≥ 85% (target).

---

## 17. Milestones & File-by-File Task List

### M5 — Scope + durable call log (foundation)

1. `core/model/SagaId.java`, `CompensationId.java`, `SagaKind`, `SagaStatus`,
   `CompensationStatus`, `SagaPolicy`, `ToolSafety`, `SagaScope`, `SagaCall`,
   `CompensationRecord`, `SagaOutcome`, `SagaContext`.
2. `core/port/store/SagaStore.java` + `core/wiring/Stores.java`.
3. `adapter/memory/InMemorySagaStore.java`.
4. `adapter/sql/AbstractJdbcSagaStore.java` + Postgres/SQLite impls.
5. `engine/src/main/resources/db/migration/V13__saga.sql`.
6. `core/model/SessionEvent.java` new records; `EventCodec` + `NativeReflectionConfig`.
7. `SagaCoordinator.begin/recordCall/commit` (no abort yet).
8. `SagaPolicy` resolver (`global → agent → node → session → request`, cap = most
   restrictive) + `SagaConfig`/`EngineConfig` fields + defaults.
9. `SagaCoordinator.disabled()` / `SagaStore.noop()` / `SagaRecovery.disabled()` and the
   `Engine` no-op wiring for `OFF`.
10. `QocEngine` records calls only when policy is `OBSERVE` or higher; in `OFF`/`MANUAL`
    no scope is created.
11. `UndoRuntime` resolves from `SagaStore` first, conversation fallback.
12. Tests: store, recording, compaction-safe lookup, policy resolution/cap, OFF no-op.

### M6 — Compensation state machine + recovery

11. `SagaPlanBuilder`, `CompensationExecutor`, `SagaRecovery`.
12. `claim` CAS + lease in `SagaStore`.
13. Deterministic idempotency key.
14. Retry/backoff/timeout.
15. `SagaCoordinator.abort` + `SagaOutcome`.
16. Tests: state machine, claim/concurrency, crash/recovery, retry.

### M7 — Automatic orchestration

17. `QocEngine` abort on failure; keep scope on `paused`.
18. `DefaultNodeDispatcher` node scope + abort.
19. Subagent parent/child scopes.
20. `SagaPermissionPolicy` + `Principal.system`.
21. Tests: turn failure, node failure, nested scopes, ASK policy.

### M8 — Coverage + backups + safety

22. `ToolSafetyRegistry` + `ToolOptions.sagaSafety` + `ToolDefinition.withSagaSafety`.
23. `InMemorySnapshotStore` (real bytes) and `NoOpSnapshotStore` replacement.
24. `FileRestoreTool` + `FileUndoSpecs`.
25. `write`/`edit`/`patch`/`delete` snapshots + undo specs.
26. `session_rename`/`session_move` compensators.
27. Irreversible policy + pre-flight + `SagaGuard`.
28. MCP manifest convention (optional, behind a flag).
29. Tests: per-tool undo, backup limits, strict/best-effort.

### M9 — API + UI

30. `SagaResource` + DTOs + permissions + idempotency.
31. `SagaAccessGuard` (ownership, batch limits).
32. React: tool card Undo badge/button, turn undo, saga panel, confirmation, polling.
33. Playwright E2E (desktop + mobile).

### M10 — Observability + hardening

34. Metrics, structured logs, retention.
35. `SagaRedactor` for secrets.
36. Chaos tests + recovery runbook.
37. Docs: `docs/spec/tools/10-saga.md`, `docs/spec/persistence/…`, `docs/betrieb/…`.
38. ArchUnit: `runtime.saga` may depend on `core`, `runtime.tool.*`; no Quarkus.

---

## 18. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Double compensation on concurrent undo | CAS claim + unique index on `call_id` + deterministic key |
| Double compensation after crash | Intent (`PENDING/STARTED`) before execution; recovery claims expired leases; compensators idempotent |
| Compaction deletes undo data | `saga_call` is independent of the conversation; record at execution time |
| Compensation fails permanently | `PARTIALLY_COMPENSATED`/`COMPENSATION_FAILED` + alert + UI retry |
| `ASK` blocks automatic compensation | Pre-flight in `STRICT`; `BEST_EFFORT` marks `AWAITING_APPROVAL`; never auto-approve |
| Irreversible tool inside a saga | `ToolSafety=IRREVERSIBLE`; strict pre-execution guard |
| Backup blows up storage | `max-bytes` per file, snapshot retention, mark undo unavailable above limit |
| Secret leakage into saga tables | `SagaRedactor`, reuse existing hooks/redaction |
| Cross-session undo abuse | ownership/lineage check + `saga:undo` permission + batch limit |
| Compensator missing after reload/MCP disconnect | registration validation + pre-flight; fall back to `COMPENSABLE`/`IRREVERSIBLE` |
| Snapshot restore outside workspace | `FileAccess` boundary check in `file_restore` |
| Recursive undo (`undo_undo_write`) | recursion guard on `undo_*` and on compensation calls |
| Workflow retry re-runs forward calls | each attempt gets a new scope; compensations are per scope |
| Long compensation blocks the turn | detached bounded executor; scope timeout; UI progress |

---

## 19. Definition of Done

- [ ] `SagaStore` (memory + SQL) with migration V13 and indexes.
- [ ] `SagaPolicy` resolution (`global → agent → node → session → request`, cap rule) with
      no-op wiring for `OFF`/`MANUAL`; the feature is fully optional and fail-closed.
- [ ] `OFF`/`MANUAL` create no scopes, no `SagaStore` rows and no saga events; `MANUAL`
      still exposes `undo_<tool>`.
- [ ] `OBSERVE` records scopes/calls but never compensates.
- [ ] Scope lifecycle (`begin/record/commit/abort`) wired in `QocEngine` and workflow nodes.
- [ ] Automatic reverse-order compensation on turn/node failure.
- [ ] Durable `CompensationRecord` state machine with CAS claim, lease, retry, recovery.
- [ ] Deterministic idempotency key; concurrent undo yields exactly one application.
- [ ] `SagaStore` is the primary undo source; compaction no longer breaks undo.
- [ ] `ToolSafetyRegistry` + explicit safety on all built-in tools.
- [ ] `write`/`edit`/`patch`/`delete` undoable via `file_restore` + snapshots.
- [ ] Irreversible policy (`STRICT`/`BEST_EFFORT`) enforced before execution.
- [ ] REST endpoints for single/batch undo, scope status, abort; permissions + ownership.
- [ ] UI: undo button/badge, turn undo, saga panel, confirmation, mobile-safe; hidden in
      `OFF`, read-only scope panel in `OBSERVE`.
- [ ] Metrics, structured logs, retention, secret redaction.
- [ ] Tests: unit, integration (all five modes), concurrency, crash/recovery, compaction, E2E.
- [ ] JaCoCo ≥ 80%; ArchUnit green.
- [ ] Docs updated (`docs/spec/tools/10-saga.md`, `docs/spec/persistence/…`, runbook).

---

## 20. Open Questions / Decisions Needed

1. **Policy default**: should `engine.saga.policy` default to `manual` (current behavior,
   zero schema writes), `observe` (recording for telemetry) or `best-effort` (automatic)?
   Recommendation: `manual` until M8 is complete, then `observe` for a rollout phase,
   then `best-effort` for selected workflows.
2. **Override cap**: should agent/node overrides only lower the global mode (recommended,
   fail-closed), or should an agent be allowed to raise it with an explicit admin flag?
   Recommendation: only lower; `engine.saga.allow-admin-override` for explicit admin
   session/request overrides.
3. **Per-session override**: should the session owner be able to change `saga.policy`, or
   only an admin? Recommendation: owner can lower, admin can raise.
4. **Automatic compensation principal**: `Principal.system()` (recommended) or the original
   agent’s principal? System is safer and auditable; the original principal may be needed for
   per-tenant policies.
5. **Irreversible shell**: block `shell` entirely in `STRICT` scopes, or allow read-only
   shell commands (needs a classifier)? Recommendation: block; read-only shell is a separate
   feature.
6. **Snapshot backend**: `SnapshotStore` (existing) vs a new `BackupStore` with content
   addressing. Recommendation: reuse `SnapshotStore` and fix `NoOpSnapshotStore`.
7. **Retention**: how long to keep `saga_scope`/`saga_call`/`saga_compensation` and
   snapshots? Recommendation: 30 days, configurable, aligned with audit retention.
8. **Cross-session undo**: allow only child sessions and admins, or also siblings?
   Recommendation: current + children + admin.
9. **Batch undo UX**: per turn, per scope, or free selection? Recommendation: per turn/scope
   first, free selection later.
10. **MCP undo manifests**: adopt the convention now or defer until M8? Recommendation:
    defer; mark MCP irreversible until then.
11. **Compensation approval**: can a human approve a compensation that was pre-flighted as
    `ASK`? Recommendation: yes, via the existing approval flow; the scope becomes
    `AWAITING_APPROVAL` and resumes on approval.
12. **Exactly-once**: accept at-least-once + idempotent compensators (recommended) or invest
    in transactional outbox? Recommendation: at-least-once; compensators must be idempotent.
