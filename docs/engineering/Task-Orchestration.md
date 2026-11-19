# Task Orchestration – Dependencies, Tiered Versions, SAGA

> **Purpose:** Detailed description of how tasks in QOC depend on each other, are
> tiered, versioned and executed as a SAGA – including durable persistence,
> cross-session wake and task quota.
>
> **Status:** 2026-11-09 (analysis / planning, not implemented)
>
> **References:** [`Async-use.md`](./Async-use.md),
> [`Core.md`](./Core.md), [`Storage-alternative.md`](./Storage-alternative.md).

## 1. Starting Point in QOC

Already available:

| Building block | File | What it can do |
|---|---|---|
| `BackgroundTasks` | `engine/.../runtime/tool/subagent/BackgroundTasks.java` | In-memory registry per parent: `create`, `succeed`, `fail`, `cancel`, `await`, `list`, `get` |
| `SubagentTool` | `.../SubagentTool.java` | `background=true` → virtual thread; `tasks=[...]` → batch (`executeBatch`, sequential, fail-fast) |
| `SubagentSessionRegistry` | `.../SubagentSessionRegistry.java` | Parent→Child mapping |
| HTTP Tasks | `HttpApi.java:513,525` | `GET /session/{id}/tasks`, `POST .../tasks/{taskId}/cancel` |
| SSE | `Engine.java:2404/2437/2448/2454` | `SubagentStarted/Finished/Cancelled` |

**What is missing:** Dependencies between tasks, tiers (Research … Saga),
version/schema versioning, compensation on partial failures, persistence across
restarts.

---

## 2. Task Dependencies (`dependsOn`)

### 2.1 Model

A task can specify a list of task IDs that must be completed before it.

```java
public record TaskNode(
    String id,
    String agent,
    String prompt,
    List<String> dependsOn,          // Task-IDs
    TaskTier tier,                   // research | analyze | execute | saga
    int version,                     // Definitionsversion
    TaskState state,
    Option<String> result,
    Instant created,
    Instant finished
) {}
```

### 2.2 Semantics

- A task with `dependsOn` does not start immediately but enters the state
  `WAITING`.
- It becomes `READY` once **all** predecessors have `SUCCEEDED`.
- If a predecessor fails (`FAILED`/`CANCELLED`), the successor becomes
  `BLOCKED` (or `CANCELLED`, depending on policy).
- A task without `dependsOn` is immediately `READY`.

**Why useful:** Prevents task B from working with incomplete data from A;
enables parallel execution of independent tasks; makes data flow explicit.

### 2.3 Concrete in QOC

- `TaskNode` gets `List<String> dependsOn`.
- `BackgroundTasks.create(parent, agent, dependsOn)` checks that all IDs
  exist.
- `tryStartReady(parent)` starts all tasks whose predecessors have all
  `SUCCEEDED` and a slot is free.

---

## 3. DAG Scheduler

### 3.1 Concept

A lightweight scheduler for directed acyclic graphs (DAG) of tasks. Tasks can
have multiple predecessors (diamond) and multiple successors (fan-out); no
cycles allowed.

### 3.2 Flow

```
1. Task is created → check predecessors → WAITING or READY
2. Scheduler finds all READY tasks (all predecessors SUCCEEDED)
3. Starts as many as there are free slots (quota)
4. On terminal event of a task:
   a. Update predecessor status
   b. Re-run tryStartReady()
   c. Possibly mark successors as BLOCKED
```

### 3.3 Advantages over `executeBatch` (today)

| `executeBatch` (today) | DAG scheduler (proposed) |
|---|---|
| Strictly sequential | Parallel where possible |
| Fail-fast (first error aborts everything) | Configurable: block or cancel |
| No result passing | Result interpolation |
| No dependencies | Explicit `dependsOn` |
| Single tool call | Multiple tasks with quota |

### 3.4 Diamond DAG Example

```
        ┌─► Task B (analyze API) ─┐
Task A (research) ─┤                  ├─► Task D (implement)
        └─► Task C (analyze DB) ─┘
```

A runs first. B and C run in parallel. D starts only once both B and C are
finished.

---

## 4. Result Interpolation

### 4.1 What

The result of a predecessor task is inserted into the prompt of a successor
task.

### 4.2 Mechanisms

| Mechanism | Description | Suitability |
|---|---|---|
| **Text interpolation** | `{{task_<id>.result}}` in the prompt is replaced by the result text | simple, readable |
| **Structured** | Result as JSON object in the prompt context | type-safe, complex |
| **Artifact reference** | `artifact://task_<id>/result` → successor reads via `read` tool | for large results |

### 4.3 Example

```
Task A (research): "Recherchiere REST-API-Best-Practices"
  → Ergebnis: "1. Pagination via cursor, 2. Rate-Limiting via Token-Bucket, ..."

Task B (analyze, dependsOn=[A]):
  Prompt: "Analyze the following best practices for relevance to QOC:
           {{task_A.result}}"
```

### 4.4 Limits

- Result size: large results should be referenced as artifacts, not inlined.
- The agent must know that it is working with an interpolated result.
- On a `FAILED` predecessor: interpolation fails → successor `BLOCKED`.

---

## 5. Tier Model (research / analyze / execute / saga)

### 5.1 What

Four levels that determine the character of a task. Each level has its own
tool access, step limits, sandbox and durability.

| Tier | Character | Tools | maxSteps | Sandbox | Durable |
|---|---|---|---|---|---|
| **research** | read-only, no side effects | read, glob, grep, webfetch, websearch | 5 | read-only | no |
| **analyze** | analysis, may write drafts | research + write/edit (scratch) | 10 | workspace-write | no |
| **execute** | execution with side effects | full catalog | 20 | configurable | yes |
| **saga** | multi-step, transactional | full catalog + compensations | 50+ | configurable | yes |

### 5.2 Why Tiers?

- **Safety:** A research task cannot destroy anything.
- **Cost:** Research needs fewer steps and no durability.
- **Clarity:** The caller states what they want ("research" vs.
  "implement").
- **Quota:** Research tasks can be parallelized more aggressively.

### 5.3 Implementation

```java
public enum TaskTier { RESEARCH, ANALYZE, EXECUTE, SAGA }

public record AgentProfile(..., TaskTier tier) { ... }
// Or at call time:
// subagent(agent="researcher", prompt="...", tier="research")
```

The tier definition controls: `maxSteps`, `tools.allow/deny`, `sandbox`,
`durable`, `compensations` (only for `saga`).

**Tier as data, not code:** JSON config or agent frontmatter, not
hardcoded `if` chains. Adjustable without rebuild.

```json
{
  "tiers": {
    "research": { "maxSteps": 5, "tools": ["read","glob","grep","webfetch","websearch"], "sandbox": "read-only", "durable": false },
    "analyze":  { "maxSteps": 10, "tools": ["read","write","edit","glob","grep"], "sandbox": "workspace-write", "durable": false },
    "execute":  { "maxSteps": 20, "tools": "*", "sandbox": "workspace-write", "durable": true },
    "saga":     { "maxSteps": 50, "tools": "*", "sandbox": "workspace-write", "durable": true, "compensations": true }
  }
}
```

---

## 6. SAGA with Compensations

### 6.1 Concept

A SAGA task consists of several **steps**, each of which has a
**compensation** (undo action). On failure, compensations run in **reverse
order**.

```
Saga: [Step1, Step2, Step3]
  Step1 OK → Comp1 registered
  Step2 OK → Comp2 registered
  Step3 FAIL → Comp2 → Comp1 (backwards)
  → Saga FAILED (with compensation status)
```

### 6.2 Data Model

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

### 6.3 Compensation Rules

1. Every completed step can have a compensation.
2. On failure: compensations in **reverse** order.
3. Compensations must be **idempotent**.
4. Compensations are logged in the journal
   (`SagaCompensationStarted/Ended`).
5. If a compensation fails: `SagaCompensationFailed` →
   manual intervention (alert).

### 6.4 Journal Events for SAGA

```
SagaStarted, SagaStepStarted, SagaStepCompleted, SagaStepFailed,
SagaCompensationStarted, SagaCompensationCompleted,
SagaCompensationFailed, SagaCompleted, SagaFailed, SagaCancelled
```

### 6.5 Persistence

- SAGA tasks **must** be durable (journal + claim).
- On restart: running SAGA → `ORPHANED`; recovery decides
  (resume or compensate).
- The `BackgroundTasks` registry alone is not enough; it must be
  supplemented by journal-based persistence.

---

## 7. Version and Schema Versioning

### 7.1 Definition Version

- Every task/saga carries `version` (integer or SemVer).
- When the definition changes, the version is incremented.
- Running tasks keep their version (no upgrade mid-flight).
- Results carry the version (`TaskResult.metadata.version`).

### 7.2 Schema Versioning

- Task payloads are JSON with an optional `schemaVersion` field.
- Missing field → assume default version.
- Migration: old payloads are normalized on load.

### 7.3 Agent Configuration

- Agent definitions already have `hash` and `updatedAt`
  (`Engine.agent()`).
- The agent hash is stored at task start.
- On resume it is checked whether the definition changed
  (warning, no automatic upgrade).

**Why important?** Without versioning, an update to agent definitions
breaks all running tasks. With versioning, old definitions can keep
running while new tasks use the new version.

---

## 8. Durable Task Persistence

### 8.1 What

Tasks survive process restarts. Currently the `BackgroundTasks` registry is
**in-memory** and loses everything.

### 8.2 Solution (Journal-based)

```
TaskStarted   { taskId, parentSession, agent, promptHash, created }
TaskHeartbeat { taskId, at, phase }
TaskProgress  { taskId, at, message, percent? }
TaskSucceeded { taskId, at, childSession, text, usage }
TaskFailed    { taskId, at, error }
TaskCancelled { taskId, at, reason }
```

### 8.3 Flow

1. Task start: `Journal.append(TaskStarted)` (durable).
2. Heartbeat: periodically `TaskHeartbeat` (durable or in-memory only).
3. Terminal: `TaskSucceeded/Failed/Cancelled` (durable).
4. `BackgroundTasks` becomes the **read model** from the journal.
5. On restart: `TaskStarted` without terminal → `ORPHANED`; recovery
   decides (restart or mark as `FAILED(orphaned)`).

### 8.4 Claim Interaction

A durable task must hold the parent session's claim while it runs. On
restart the claim is recognized as "open" and task recovery is started.

---

## 9. Cross-Session Wake on Task Completion

### 9.1 What

When a background task (e.g. subagent) finishes, the parent session is
**automatically woken**, instead of polling.

### 9.2 Today vs. Goal

| Aspect | Today | Goal |
|---|---|---|
| Mechanism | `taskPoll` (`index.html:1331`), polling every 5 s | Event-driven |
| Latency | up to 5 s | immediate |
| Parent state | must be running | is woken |
| Result delivery | fetch via `GET /tasks` | Inbox delivery (`steer`) |

### 9.3 Flow (Goal)

```
Task finished → Journal.append(TaskSucceeded)
             → InboxEnqueued(parent, result)
             → SessionWake.wake(parent)
             → Parent step: Delivery → new step with result
```

### 9.4 Advantages

- No polling, no latency.
- Parent can do other work or sleep in the meantime.
- Result arrives as a `steer` item in the inbox.

### 9.5 Limits

- Result size: large results as `ArtifactRef` in the inbox, not
  inline.
- Ordering: Delivery happens at safe step boundaries.
- The parent must be able to run to process the result (or is woken at the
  next wake).

---

## 10. Task Quota per Session

### 10.1 What

Limit on how many background tasks a session may have at the same time.

### 10.2 Today vs. Proposal

| Quota | Today | Proposal |
|---|---|---|
| Global (all sessions) | 4 (`Engine.java:2249`) | configurable |
| Per session | – | 2 (new) |
| Per parent (depth) | max. 2 (`DEFAULT_MAX_DEPTH`) | stays |
| Per task queue | – | 100 (new) |
| Task runtime (hard limit) | – | configurable (new) |

### 10.3 Enforcement

```java
public String create(SessionId parent, String agent, List<String> dependsOn) {
    int running = runningCount(parent);
    if (running >= perSessionQuota) {
        throw new TaskQuotaExceededException(parent, running, perSessionQuota);
    }
    ...
}
```

**Backpressure:** When the queue is full, the call is rejected
(`429`/`TaskQuotaExceeded`), not blocked. Blocking would occupy the
execution thread.

---

## 11. Implementation in QOC: what exists, what is missing

| Capability | Status | File |
|---|---|---|
| Background-task registry (in-memory) | ✅ | `BackgroundTasks.java` |
| Virtual-thread execution | ✅ | `SubagentTool.java:183` |
| Batch execution (sequential) | ✅ | `SubagentTool.executeBatch` |
| Task status/wait/cancel/list | ✅ | `TaskTool.java` |
| Parent/child tracking | ✅ | `SubagentSessionRegistry` |
| Live events (start/finish/cancel) | ✅ | `Engine.java:2404-2454` |
| HTTP tasks endpoints | ✅ | `HttpApi.java:513,525` |
| Task dependencies (`dependsOn`) | ❌ | new |
| DAG scheduler | ❌ | new |
| Result interpolation | ❌ | new |
| Tier model (research/analyze/execute/saga) | ❌ | new |
| SAGA with compensations | ❌ | new |
| Version/schema versioning | ❌ | new |
| Durable task persistence | ❌ | new (see `Async-use.md`) |
| Cross-session wake on task completion | ❌ | new |
| Task quota per session | ❌ | new |

---

## 12. Implementation Plan (phased)

1. **Task dependencies** (`dependsOn`, DAG scheduler, result interpolation)
2. **Tier model** (research/analyze/execute/saga)
3. **SAGA** with compensations
4. **Version/schema versioning**
5. **Durable task persistence** (journal)
6. **Cross-session wake** (inbox delivery)
7. **Task quota per session**

Each phase builds on the previous one. Phases 1+2 are relatively small and
high-value. Phase 3 (SAGA) is the most complex and should only be
implemented after durable persistence (phase 5).

---

## 13. Example: Research → Analyze → Execute → Saga

```
Task A (research)   → researches facts
  └─ Task B (analyze, dependsOn=[A]) → analyzes A's result
       └─ Task C (execute, dependsOn=[B]) → implements
            └─ Task D (saga, dependsOn=[C]) → multi-step with compensation
```

- A: read-only, 5 steps, no durability.
- B: write in scratch, 10 steps.
- C: full catalog, 20 steps, durable.
- D: 50 steps, compensations on failure.

On failure in C: B and A remain (no compensation needed, since C has not
completed yet). On failure in D: compensations for D's completed steps,
then C's compensation (if defined).

---

## 14. Open Questions

1. Should `dependsOn` refer to task IDs or session IDs?
2. Should a failed predecessor **block** or **cancel** the successor
   (policy per task)?
3. Should the tier model be hard (tool filter) or soft (only maxSteps)?
4. Who defines SAGA compensations – the agent, the user, or code?
5. Should a task result be an `ArtifactRef` or text in the inbox?
6. How to handle parallel predecessors (`dependsOn=[A,B]`) – do both have
   to SUCCEEDED?
7. Should there be a global task queue or only per-session registries?

## 15. References

- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/BackgroundTasks.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/TaskTool.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentSessionRegistry.java`
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` (Subagent wiring, live events, semaphore)
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java` (Tasks endpoints)
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java` (Claim, doorbell)
- [`Async-use.md`](./Async-use.md) (Variant B/C, heartbeat, recovery)
- [`Core.md`](./Core.md) (Coordinator, journal, inbox)
- [`Storage-alternative.md`](./Storage-alternative.md) (Persistence, journal as source of truth)