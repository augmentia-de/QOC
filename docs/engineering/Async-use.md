# Async-Use – asynchronous agent calls and heartbeat

> **Purpose:** Detailed analysis and implementation options for asynchronous
> agent calls in QOC – including heartbeat, status model, recovery,
> API surface, and phased plan.
>
> **Status:** 2026-11-09
>
> **Scope:** This document describes options and the current
> state of the art. It does not replace the spec. In case of contradictions,
> [`spec/session-engine/`](../spec/session-engine/README.md) applies, in particular
> [`12-execution-model.md`](../spec/session-engine/12-execution-model.md).

## 1. Terms: async at which level?

| Level | Meaning | Example |
|---|---|---|
| **Tool-Async** | A tool starts background work and delivers a result later. | `subagent(background=true)`, long build, web scrape |
| **Step-Async** | Within a step, tool calls run in parallel. | `Step` executes up to 8 tool calls concurrently |
| **Execution-Async** | An execution runs on a virtual thread; wakes are coalesced via the doorbell. | `Coordinator.wake` |
| **Session-Async** | Multiple sessions run in parallel without blocking each other. | `wake` per session |
| **Cross-Session-Async** | A session reacts to results of another (parent/child). | Subagent → Parent-Inbox |
| **Extern-Async** | Triggers from outside (webhook, email, A2A, scheduler). | A2A-Agent, Webhook |

**Guiding rule:** Async is not an end in itself. The core remains
**synchronous** per step (one model call); asynchrony arises between steps,
executions, sessions, and external triggers.

## 2. Current state in QOC

### 2.1 Background subagent (available)

| Component | File/path | Behavior |
|---|---|---|
| `BackgroundTasks` | `engine/.../runtime/tool/subagent/BackgroundTasks.java:22` | In-memory registry per session; `create` (`:53`), `attach` (`:60`), `succeed` (`:68`), `fail` (`:80`), `cancel` (`:91`), `await` (`:115`), `closeSession` (`:130`), `closeAll` (`:144`). |
| `SubagentTool.executeBackground` | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java:164` | Starts subagent on a virtual thread (`:183`), returns `taskId` immediately. |
| `TaskTool` | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/TaskTool.java:32` | `status`/`wait`/`cancel`/`list`; `wait` recommends timeout ≥ 30 s (`:39`). |
| Engine-Wiring | `quarkus/.../Engine.java:2249` | `MAX_CONCURRENT_SUBAGENTS = 4`, Semaphore (`:2252`), `tryAcquire(5 min)` (`:2370`). |
| Task-Tracking | `Engine.java:2383-2384` | Synchronous subagents are also registered as tasks (`create`/`attach`). |
| Live-Events | `Engine.java:2404`, `:2437`, `:2448`, `:2454` | `SubagentStarted`, `SubagentFinished`, `SubagentCancelled` via `EventBus.publishLive`. |
| HTTP-Surface | `HttpApi.java:513`, `:525` | `GET /api/session/{id}/tasks`, `POST /api/session/{id}/tasks/{taskId}/cancel`. |
| Tool-Timeout Exception | `ToolRuntime.java:98-99` | `subagent`/`task` are exempt from step timeout (`longRunning`). |

### 2.2 Further async mechanisms

| Mechanism | Location | Note |
|---|---|---|
| **Tool parallelism in step** | `Step.java:35` (`MAX_PARALLEL_TOOLS=8`) | `question` is deliberately executed sequentially (`Step.java:128-131`). |
| **Execution-Doorbell** | `Coordinator.java:40-96` | One wake per session; coalescing; follow-up execution. |
| **ExecutionRunner** | `ExecutionRunner.java` | Interface present; drain loop in core not yet ported. |
| **SessionWake** | `core/port/runtime/SessionWake.java` | Decouples admission → coordinator. |
| **A2A** | `quarkus/.../http/a2a/*` | Agent-to-agent protocol, own auth mechanism. |
| **MCP** | `engine/.../runtime/tool/mcp/*` | External tool servers (JSON-RPC). |
| **SSE** | `HttpApi.java:304` (`/event`), `:326` (`/events`) | Live event stream; UI uses fetch-based SSE (`index.html:1622`). |
| **UI-Orchestrator-Heartbeat** | `index.html:611` (`initOrchestratorHeartbeat`) | `BroadcastChannel('qoc-orchestrator')`; parent/subagent view; 2 s ping, 10 s timeout (`:636`). |
| **UI-Polling** | `index.html:1181` (`checkHealth`), `:1331` (`taskPoll`), `:1750`/`:1752` (`permPoll`/`questionPoll`, 700 ms), `:1042` (`startQuestionsPoll`, 5 s) | Fallback where no SSE is used. |

### 2.3 Known gaps

1. **Not durable:** `BackgroundTasks` is in-memory; a restart loses
   all running tasks and results (`BackgroundTasks.java:19-20`).
2. **No server heartbeat:** There are live events on start/end, but no
   periodic "execution still alive" signal.
3. **No task progress:** Long-running tools/subagents report no
   intermediate status.
4. **No cross-session wake on task end:** The parent must poll
   (`task wait` or UI polling) instead of being woken via the inbox.
5. **No scheduler/webhook trigger:** External events cannot start
   a session (webhook docs exist, but not wired as async trigger).
6. **No per-session quota:** Only the global subagent limit (4).

## 3. Where async makes sense

| Scenario | Async level | Benefit | Risk |
|---|---|---|---|
| **Subagent delegation** (research, review) | Tool-/Cross-Session | Parent continues working | orphaned tasks on restart |
| **Long-running tools** (build, test, migration) | Tool | no step timeout, UI stays usable | result delivery complex |
| **A2A remote agents** | Extern/Cross-Session | other processes/hosts | auth, network, idempotency |
| **MCP tool servers** | Tool | I/O parallelism | server outage |
| **Compaction/title-gen** | Execution-intern | non-blocking | – (already async) |
| **Webhooks/email triggers** | Extern | agent reacts to events | auth, replay, duplicates |
| **Scheduled runs** (cron) | Extern | periodic tasks | overlap, claims |
| **Permission/question wait** | Execution | blocks only the session, not the server | hanging approvals |

## 4. Where async does NOT make sense

| Area | Reason |
|---|---|
| **Permission check** | Must decide before the side effect (synchronous, deny-first). |
| **Tool input decode/validation** | Fail-fast, no side effects. |
| **Admission/delivery** | Transactional and serialized per session (inbox semantics). |
| **`ToolCalled` publication** | G-03: durable before the side effect. |
| **Auto-compaction before request** | Must be completed before the model call. |
| **Claim/release** | Atomic; no fire-and-forget. |

## 5. Implementation variants

### Variant A – In-memory (status quo, extend)

**Idea:** `BackgroundTasks` remains the registry; extended with heartbeat and
progress events, no persistence.

| Pro | Contra |
|---|---|
| No storage effort | Loss on restart |
| Simple, fast | No multi-node |
| Fits short-lived tasks | No audit/replay |

**Suitable for:** UI-near, short-lived subagents; development.

### Variant B – Durable task in the journal (recommended)

**Idea:** Each background task is tracked as an aggregate in the `Journal`:
`TaskStarted`, `TaskHeartbeat`, `TaskProgress`, `TaskSucceeded`,
`TaskFailed`, `TaskCancelled`. Task state is reconstructable from the events;
`BackgroundTasks` becomes the read model.

| Pro | Contra |
|---|---|
| Survives restart | more write load |
| Auditable | recovery logic needed |
| Fits event sourcing | task IDs as aggregates |
| No new storage | – |

**Components:**

```
TaskStarted   { taskId, parentSession, agent, promptHash, created }
TaskHeartbeat { taskId, at, phase }
TaskProgress  { taskId, at, message, percent? }
TaskSucceeded { taskId, at, childSession, text, usage }
TaskFailed    { taskId, at, error }
TaskCancelled { taskId, at, reason }
```

**Recovery:** On start, find `TaskStarted` without a terminal event →
status `UNKNOWN`/`STALLED`; depending on policy, restart (idempotently) or
mark as `FAILED(orphaned)`.

### Variant C – Event-driven via inbox/delivery

**Idea:** The task result is delivered as an inbox item (`steer`) into the
parent session. The parent is woken instead of polling.

| Pro | Contra |
|---|---|
| Fits existing inbox/delivery semantics | Task result must be modelled as a message |
| No polling | Order/delivery mode must be respected |
| Parent can continue working | Result size limited (ArtifactRef) |

**Flow:**

```
Parent ── subagent(background) ──► TaskStarted
                                   │
                                   ▼
                            Child-Session runs
                                   │
                     TaskSucceeded + InboxEnqueued(parent, result)
                                   │
                                   ▼
                     Parent-wake → Delivery → Step
```

### Variant D – External queue (multi-node)

**Idea:** Tasks in Redis Streams, NATS JetStream, or SQS; worker processes
consume; lease/heartbeat in the queue system.

| Pro | Contra |
|---|---|
| Scalable, multi-node | Operational effort |
| Retry/DLQ built in | new infrastructure |
| Decoupling | Consistency with journal needed |

**Only makes sense when** multiple instances or high task load exist.

### Variant E – Scheduler/webhook trigger (external)

**Idea:** An external trigger (cron, webhook, email) calls
`Admission.admit(...)` + `wake(...)`; the session runs like a normal
prompt.

| Pro | Contra |
|---|---|
| Reuse of admission pipeline | Auth/replay protection needed |
| No new execution path | Duplicates → idempotency key |

## 6. Recommended target architecture

**Combination: B + C + Heartbeat + SSE.**

```
┌────────────────────────────────────────────────────────────┐
│ Parent-Session                                             │
│  subagent(background=true)                                 │
│    └─ TaskService.start(...)                               │
│         ├─ Journal.append(TaskStarted)                     │
│         ├─ TaskRunner.submit(virtual thread)               │
│         └─ return taskId                                   │
│                                                            │
│  TaskRunner                                                │
│    ├─ HeartbeatPublisher (every 5 s)                        │
│    ├─ Child-Session (runPrompt)                            │
│    ├─ Journal.append(TaskSucceeded/Failed)                 │
│    ├─ InboxEnqueued(parent, result)   ◄─ Variant C        │
│    └─ SessionWake.wake(parent)                             │
│                                                            │
│  Parent-Step                                               │
│    └─ Delivery → new Step with Subagent result             │
└────────────────────────────────────────────────────────────┘
```

**New/extended components:**

| Component | Type | Task |
|---|---|---|
| `TaskService` | Runtime | Start/stop/status of background tasks; uses `Journal` + `TaskRunner`. |
| `BackgroundTaskStore` | Port (optional) | Persistence/query of tasks; can build on `Journal`/`SessionStore`. |
| `TaskRunner` | Runtime | Virtual threads, quota, interrupt, heartbeat. |
| `HeartbeatPublisher` | Runtime | Periodic `TaskHeartbeat`/`ExecutionHeartbeat` events. |
| `TaskStatus` | Model | `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `STALLED`, `ORPHANED`. |
| `TaskResultDelivery` | Runtime | Result as inbox item in the parent. |

## 7. Heartbeat design

### 7.1 Heartbeat kinds

| Kind | Purpose | Interval | Timeout | Event |
|---|---|---|---|---|
| **Execution-Heartbeat** | Is the execution still alive? | 10–30 s | 3× interval | `ExecutionHeartbeat` |
| **Task-Heartbeat** | Is the background subagent still running? | 5–15 s | 3× interval | `TaskHeartbeat` |
| **Tool-Progress** | Progress of a long tool | event-based | – | `ToolProgress` |
| **Worker-Lease** | Multi-node: instance holds session | 10 s | 30 s (lease TTL) | `ClaimRenewed` |
| **Provider-Stream** | LLM stream stalls | SSE timeout | configuration | `ProviderTimeout` |

### 7.2 Thresholds and actions

| State | Condition | Action |
|---|---|---|
| `RUNNING` | Heartbeat < 2× interval | normal |
| `SUSPECT` | Heartbeat > 2× interval | UI warning, metric |
| `STALLED` | Heartbeat > 3× interval | warning + optional interrupt |
| `ORPHANED` | Restart, no terminal event | recovery or `FAILED(orphaned)` |
| `CANCELLED` | User/shutdown | interrupt + terminal event |

### 7.3 UI behavior (extend existing)

- **Orchestrator-Heartbeat** (`index.html:611`): remains for parent/subagent view.
- **Task-Heartbeat** via SSE: gradually replaces `taskPoll` (`index.html:1331`);
  polling remains fallback.
- **Stalled banner**: analogous to `updateFallbackBanner` (`index.html:636`).
- **Heartbeat indicator**: dot/timestamp per task in the task list.

### 7.4 Heartbeat and claims

- A heartbeat does **not** automatically extend a claim lease; that is
  explicitly the job of `ClaimStore.renew` (new) or the worker lease.
- On `STALLED`, a second execution must **not** be started automatically
  (claim remains). Recovery decides.

## 8. API surface

### 8.1 Tools

| Tool | Action | Status |
|---|---|---|
| `subagent` | `background=true` starts task | ✅ available |
| `task` | `status`, `wait`, `cancel`, `list` | ✅ available |
| `task` (new) | `progress`, `heartbeat` | ❌ planned |

### 8.2 HTTP

| Method | Path | Status |
|---|---|---|
| `GET` | `/api/session/{id}/tasks` | ✅ |
| `POST` | `/api/session/{id}/tasks/{taskId}/cancel` | ✅ |
| `GET` | `/api/session/{id}/tasks/{taskId}` | ❌ optional |
| `GET` | `/api/session/{id}/tasks/{taskId}/events` | ❌ optional (SSE) |

### 8.3 SSE events (proposal)

```
TaskStarted   { taskId, parent, agent, childSession }
TaskHeartbeat { taskId, at, phase, progress? }
TaskProgress  { taskId, at, message, percent? }
TaskSucceeded { taskId, at, text, usage }
TaskFailed    { taskId, at, error }
TaskCancelled { taskId, at, reason }
ExecutionHeartbeat { session, at, step }
```

Existing events (`SubagentStarted/Finished/Cancelled`,
`Engine.java:2404/2437/2448/2454`) can be kept or migrated into the new
schema.

## 9. Failure and recovery semantics

| Case | Behavior |
|---|---|
| **Process crash during task** | `TaskStarted` without terminal → `ORPHANED`; recovery decides: restart (idempotently) or `FAILED(orphaned)`. |
| **User cancel** | Interrupt + `TaskCancelled` + claim/child cleanup. |
| **Shutdown** | Interrupt, but parent session claim remains (X-7); tasks are marked `STALLED`. |
| **Duplicate result** | Task ID + idempotency: only the first terminal event counts. |
| **Child session gone** | `TaskFailed(orphaned)`; parent is informed. |
| **Result too large** | Only `ArtifactRef` into the inbox; full text in `ToolOutputStore`. |
| **Parent finished** | Task is cancelled (`closeSession`, `BackgroundTasks.java:130`) or continues as a standalone session (policy). |

**At-least-once:** Async tasks inherit the core's recovery semantics.
Tools/side effects must be idempotent or deduplicated via `IdempotencyKey`.

## 10. Quotas and backpressure

| Limit | Suggestion | Location |
|---|---|---|
| Global subagent concurrency | 4 (current), configurable | `Engine.java:2249` |
| Per session | e.g. 2–4 background tasks | new, `TaskService` |
| Per parent | max. depth 2 (current) | `SubagentTool.DEFAULT_MAX_DEPTH` |
| Task queue | bounded (e.g. 100), rejection with error | `TaskService` |
| Task runtime | optional hard limit | `TaskRunner` |
| Heartbeat frequency | 5–15 s, throttled | `HeartbeatPublisher` |

Backpressure arises **in the inbox/queue**, not in threads.

## 11. Observability

| Metric | Question |
|---|---|
| `tasks_active` | How many background tasks are running? |
| `tasks_by_state` | Distribution `RUNNING`/`STALLED`/`FAILED`? |
| `task_duration` | How long do tasks take? |
| `heartbeat_age` | How old is the last heartbeat? |
| `tasks_orphaned_total` | How often recovery? |
| `task_cancel_total` | Cancels per reason |
| `subagent_slots_used` | Semaphore utilization |

Logs: Task ID and parent session ID in every line.

## 12. Security and permissions

- `subagent(background=true)` goes through the same `PermissionGate`
  (`SubagentTool.java:170-180`).
- Task status/cancel only for the own parent session
  (`BackgroundTasks.entry` checks `parent`, `:156-160`).
- HTTP endpoints inherit the API skin's OIDC/Basic auth.
- Results may contain sensitive data → same policy as normal
  tool outputs (retention, redaction).

## 13. Phased plan

### Phase 1 – Heartbeat (P0, small)

1. `HeartbeatPublisher` in `Engine.prompt`/`TaskRunner` (10–30 s).
2. SSE event `ExecutionHeartbeat`/`TaskHeartbeat` via `EventBus.publishLive`.
3. UI: heartbeat display + stalled banner; polling remains fallback.
4. Tests: heartbeat appears, timeout marks `SUSPECT`/`STALLED`.

### Phase 2 – Durable tasks (P0/P1)

5. `TaskStarted`/`TaskHeartbeat`/`TaskSucceeded`/`TaskFailed`/`TaskCancelled`
   in the `Journal`.
6. `BackgroundTasks` as read model from the journal; recovery on start.
7. `TaskService` as central API (start/status/cancel/list).
8. Tests: restart mid-task, orphan detection, cancel.

### Phase 3 – Result delivery (P1)

9. `TaskSucceeded` → `InboxEnqueued(parent, result)` + `SessionWake.wake`.
10. Parent step consumes the result as a steer (no polling needed).
11. Size limit/ArtifactRef.

### Phase 4 – Tool-async (P2)

12. `ToolRuntime` path for long-running tools: immediate `taskId`, result via inbox.
13. `ToolProgress` events.
14. Timeout/cancel semantics for external tools (MCP/A2A).

### Phase 5 – External triggers/multi-node (P2)

15. Webhook/scheduler → `Admission` + `wake`.
16. Optional external queue (Redis/NATS/SQS) and worker lease.

## 14. Test strategy

| Test | Checks |
|---|---|
| Unit: `BackgroundTasks` state machine | `RUNNING` → `SUCCEEDED`/`FAILED`/`CANCELLED`; late result ignored |
| Unit: Heartbeat | Interval, timeout, `SUSPECT`/`STALLED` |
| Integration: Restart | `TaskStarted` without terminal → `ORPHANED`/recovery |
| Integration: Cancel | Interrupt, terminal event, child cleanup |
| E2E: Parent does not wait | Parent step continues; result arrives as steer |
| E2E: UI | SSE heartbeat visible; polling fallback |
| Load: Quota | Max. concurrency never exceeded |

## 15. Open questions

1. Should a background task be its **own session** (as today) or a
   task aggregate without a session?
2. Should the parent be **automatically woken** on task end (variant C)
   or explicitly call `task wait`?
3. How long should task events/results be retained?
4. Should a `STALLED` task be cancelled automatically or only marked?
5. Is task priority/queue ordering needed?
6. Should webhooks/scheduler be considered in phases 1–5 or separately?

## 16. References (code)

| Topic | Path:line |
|---|---|
| `BackgroundTasks` | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/BackgroundTasks.java:22` |
| Background start | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java:164`, virtual thread `:183` |
| Task tool | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/TaskTool.java:32`, `:39` |
| Subagent limit | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:2249`, `:2370` |
| Task registration | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:2383` |
| Live events | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:2404`, `:2437`, `:2448`, `:2454` |
| HTTP tasks | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java:513`, `:525` |
| Tool-timeout exception | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java:98` |
| Step parallelism | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java:35`, `:128` |
| Coordinator/doorbell | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java:40` |
| SSE endpoints | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java:304`, `:326` |
| UI heartbeat | `qoc/quarkus/src/main/resources/META-INF/resources/index.html:611`, `:636` |
| UI polling | `qoc/quarkus/src/main/resources/META-INF/resources/index.html:1181`, `:1331`, `:1750`, `:1752`, `:1042` |
| SSE client | `qoc/quarkus/src/main/resources/META-INF/resources/index.html:1622`, `:1629`, `:1671` |
