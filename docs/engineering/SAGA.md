# SAGA – Durable Multi-Step Execution with Compensations

> **Purpose:** Explains how a SAGA task is executed, persisted, recovered, and
> compensated in QOC. Based on the concepts in
> [`Task-Orchestration.md`](./Task-Orchestration.md) §6 and the existing
> recovery implementation in `engine/runtime/execution/Recovery.java`.
>
> **Status:** 2026-11-10 – Concept + storage requirements documented;
> `Recovery` is wired up, a dedicated `SagaStore` does not yet exist.
>
> **Update 2026-11-22:** Tool-level undo is implemented
> ([`plans/saga-undo-tools.md`](../plans/saga-undo-tools.md)) and the complete
> tool-level saga is planned in
> [`plans/saga-orchestration.md`](../plans/saga-orchestration.md). This document
> describes the **task/workflow-step-level** SAGA; its `SagaStore` and durable
> step compensations are still not implemented. The two levels share the
> compensation idea but operate on different units (workflow step vs. tool call).
>
> **Code references:**
> - `engine/runtime/execution/Recovery.java` – Crash recovery (Claims)
> - `engine/runtime/execution/Coordinator.java` – Claim acquire/release
> - `engine/runtime/QocEngine.java` – `recover()`, Claim guard in `runPrompt`
> - `quarkus/.../http/Engine.java` – invocation of `qocEngine.recover()` at startup
> - `temporal/.../workflow/SagaWorkflowImpl.java` – Temporal example (toy)

---

## 1. What is a SAGA in QOC?

A SAGA is a **multi-step task**, where each step can have a
**compensation** (undo action). If a step fails, the compensations of the
already completed steps are executed in **reverse order**.

```
Saga: [Step1, Step2, Step3]
  Step1 OK  → Comp1 registered
  Step2 OK  → Comp2 registered
  Step3 FAIL → Comp2 → Comp1 (backwards)
  → Saga FAILED (with compensation status)
```

Properties:

- **Durable**: State must survive restarts.
- **At-least-once with idempotency**: Steps and compensations may be
  re-triggered during recovery, but must be idempotent.
- **Journal-based**: Every transition is logged as an event.
- **Claim-protected**: Exactly one active execution per session/saga.

## 2. Data Model

```java
public record SagaStep(
    String name, String agent, String prompt,
    String compensationAgent, String compensationPrompt) {}

public record SagaDefinition(
    String id, int version, List<SagaStep> steps, List<String> dependsOn) {}

public record SagaExecution(
    String sagaId, String taskId, int currentStep,
    List<String> completedSteps, List<String> compensationsRun, SagaState state) {}
```

States (`SagaState`):

```
RUNNING → (error) → COMPENSATING → (compensations done) → FAILED | COMPLETED
CANCELLED (manual)
ORPHANED (after crash, before recovery)
MANUAL_INTERVENTION (compensation failed)
```

## 3. Journal Events for SAGA

All transitions are recorded as `SessionEvent`/`Payload` in the journal:

```
SagaStarted
SagaStepStarted
SagaStepCompleted
SagaStepFailed
SagaCompensationStarted
SagaCompensationCompleted
SagaCompensationFailed
SagaCompleted
SagaFailed
SagaCancelled
```

Plus the normal execution events:

```
ExecutionStarted
ExecutionSucceeded
ExecutionFailed
ExecutionInterrupted
SyntheticRaised   (recovery hint to the model)
```

The journal is the **source of truth**. Projections (Session, Conversation,
Saga-State) are read models.

## 4. Required Storage Stores

### P0 – durable before every side effect

| Store | Tables | Rationale |
|---|---|---|
| `journal` | `event`, `event_sequence` | Timeline, replay, recovery decisions |
| `claims` | `session_claim`, `resume_counter` | Exactly one active execution; crash detection |
| `sessions` + `sessionReader` | `session`, `session_inbox` | Identity, agent, location, inbox |
| `conversation` | `session_message` | Context for each step |
| **new `SagaStore`** | see §5 | Materialized state for fast recovery |
| **durable `IdempotencyStore`** | see §6 | Exactly-once for forward/compensation steps |

### P1 – must survive restart

| Store | Tables | Rationale |
|---|---|---|
| `approvals` | `saved_approval`, `pending_approval` | HITL steps |
| `instructions` | `instruction_state`, `instruction_entry`, `instruction_blob` | Reproducibility |
| `workspaces` | columns in `session` | Workspace per step, pause state |
| `credentials` | `credential` | External calls |

### P2 – non-critical / derivable

| Store | Tables | Rationale |
|---|---|---|
| `snapshots` | `snapshot`, `snapshot_file` | File compensation / revert |
| `tool-outputs` | `tool_output` | Evidence, not state |
| `tool-cache` | `tool_result_cache` | Performance; no durability guarantee |

## 5. Proposal: SagaStore Schema

```sql
create table saga_definition (
  saga_id     text primary key,
  version     int  not null,
  definition  jsonb not null,
  created_at  timestamptz not null default now()
);

create table saga_execution (
  saga_id      text primary key,
  task_id      text not null,
  state        text not null,          -- RUNNING | COMPENSATING | COMPLETED | FAILED | CANCELLED | ORPHANED | MANUAL_INTERVENTION
  current_step int  not null default 0,
  version      int  not null,
  owner_id     text,
  updated_at   timestamptz not null default now()
);

create table saga_step (
  saga_id    text not null,
  step_no    int  not null,
  name       text not null,
  state      text not null,            -- PENDING | RUNNING | COMPLETED | FAILED | COMPENSATED
  attempts   int  not null default 0,
  idem_key   text not null,
  error      text,
  updated_at timestamptz not null default now(),
  primary key (saga_id, step_no)
);

create table saga_compensation (
  saga_id    text not null,
  step_no    int  not null,
  state      text not null,
  attempts   int  not null default 0,
  idem_key   text not null,
  error      text,
  updated_at timestamptz not null default now(),
  primary key (saga_id, step_no)
);
```

The journal remains the source of truth; `saga_execution`/`saga_step` are
materialized read models for fast recovery.

## 6. Idempotency – do not use the tool cache

`tool_result_cache` is **not** a durability guarantee:

- only populated when an `IdempotencyKey` is set
- deleted after 1 day (`RetentionService`)
- may be missing in memory mode

SAGA requires its own table:

```sql
create table idempotency_record (
  scope       text not null,          -- saga | tool | compensation
  key         text not null,
  status      text not null,          -- STARTED | SUCCEEDED | FAILED
  result      jsonb,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  primary key (scope, key)
);
```

Rule: Write `STARTED` **before** every side effect, then
`SUCCEEDED`/`FAILED`. During recovery, a `STARTED` record is found and the
action is not blindly repeated.

## 7. Compensation Rules

1. Every completed step may have a compensation.
2. On failure: compensations in **reverse** order.
3. Compensations must be **idempotent**.
4. Compensations are recorded in the journal
   (`SagaCompensationStarted/Ended`).
5. If a compensation fails: `SagaCompensationFailed` → state
   `MANUAL_INTERVENTION` + alert.

## 8. Restart/Recovery Flow

### Claim-based recovery (exists)

`Recovery.recover()` iterates over open claims:

1. Read `claims.open()`.
2. If the session is active in this process → skip.
3. `resumeAttempts >= MAX_RESUME_ATTEMPTS` (10) → `ExecutionFailed`, release claim.
4. Otherwise: append `SyntheticRaised` ("server was restarted"), release claim,
   then run `runPrompt` with the synthetic prompt.
5. `runPrompt` acquires a new claim and runs normally.

### SAGA recovery (to be implemented)

1. `select * from saga_execution where state in ('RUNNING','COMPENSATING')`.
2. Acquire lease/claim via CAS on `owner_id` + heartbeat.
3. Reconstruct step status from `saga_step` and journal.
4. `RUNNING` → resume from `current_step`; before each step persist
   `SagaStepStarted` + idempotency key.
5. On error → `state = COMPENSATING`; compensations backwards; persist each
   compensation durably.
6. Compensation failed → `SagaCompensationFailed`, state
   `MANUAL_INTERVENTION`, alert.
7. Terminal: `SagaCompleted`/`SagaFailed`/`SagaCancelled`.

### Current State

- `Recovery` is wired up (`QocEngine.recover()`, invocation in
  `Engine.wire()`), but uses `stores.claims()` and `Coordinator`.
- `QocEngine.runPrompt` acquires/releases claims; execution events are
  appended (`ExecutionStarted`, `ExecutionSucceeded`, `ExecutionFailed`).
- `QocEngine.recover()` resumes open claims with a synthetic prompt.
- There is **no** `SagaStore` and **no** saga events in `SessionEvent`.
- SAGA exists only as a Temporal example (`SagaWorkflowImpl`).

## 9. Alternative: Temporal

If SAGA runs via Temporal, **Temporal itself** is the durable store:

- Workflow history, retries, timers, signals, and activity results live in
  Temporal.
- QOC then only needs `journal` + `conversation` for trace and agent context.
- No QOC `SagaStore` needed.
- The existing `SagaWorkflowImpl` is only an example and is not connected to
  the QOC engine.

## 10. Configuration for Durable Runs

```properties
ENGINE_STORAGE=postgres          # or sqlite
ENGINE_RECOVERY_ENABLED=true
ENGINE_AUDIT_RETENTION_DAYS=0   # no event deletion
SESSION_RETENTION_DAYS=0        # no session deletion / no paused sweep
ENGINE_REVERT_SNAPSHOT=true
```

Note: `SESSION_RETENTION_DAYS=0` disables the entire
`RetentionService` executor, including audit purge and paused sweep. This is
desired for SAGA/long-running tasks.

## 11. Risks and Trade-offs

| Topic | Risk | Mitigation |
|---|---|---|
| At-least-once | duplicate side effects | Idempotency keys + `idempotency_record` |
| Claim leakage | session blocked after crash | `Recovery` + `MAX_RESUME_ATTEMPTS` |
| Compensation failed | manual intervention | `SagaCompensationFailed` + alert |
| Retention | states get deleted | retention set to 0 |
| Multi-node | SQLite not shareable | Phase 4: Redis/etcd/Kafka |

## 12. Open Questions

1. Should SAGA be implemented in QOC itself or in Temporal?
2. Is `SagaStore` modeled as its own port or as a projection from the journal?
3. How are compensations defined – by the agent, the user, or code?
4. Should `IdempotencyStore` replace or complement `ToolResultCache`?
5. Should snapshots be used for file compensation or explicit compensation
   scripts?

## 13. Sources

| Topic | Path |
|---|---|
| SAGA concept | `qoc/docs/engineering/Task-Orchestration.md` §6 |
| Storage priorities | `qoc/docs/engineering/Storage-alternative.md` §4 |
| Recovery implementation | `qoc/engine/.../execution/Recovery.java` |
| Coordinator/Claims | `qoc/engine/.../execution/Coordinator.java` |
| QocEngine recovery | `qoc/engine/.../runtime/QocEngine.java` (`recover()`) |
| Temporal saga | `qoc/temporal/.../workflow/SagaWorkflowImpl.java` |
| Retention | `qoc/quarkus/.../http/RetentionService.java` |
