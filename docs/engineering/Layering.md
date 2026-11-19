# Layering – Agent Runtime vs. Workflow Orchestration

> **Purpose:** Defines the layer boundary between the Agent Runtime (core)
> and workflow orchestration. Clarifies where subagents/tools live and where
> resilience, SAGA, dependencies and cross-session wake belong.
>
> **Status:** 2026-11-09 (Decision)
>
> **References:** [`Core.md`](./Core.md),
> [`Task-Orchestration.md`](./Task-Orchestration.md),
> [`Async-use.md`](./Async-use.md),
> [`Storage-alternative.md`](./Storage-alternative.md).

## 1. Decision

**Both layers make sense.** Subagents live in the **core**, but only as
**helpers (primitives)** – without resilience, saga or dependencies.
Workflow orchestration sits **on top** and uses subagents as **workers**.

| Level | Question | Knows subagents/tools as … |
|---|---|---|
| **Agent Runtime (core)** | "How do I execute **one** turn?" | **Tool** – `subagent` is a tool the agent can call. |
| **Workflow layer (top)** | "How do I distribute **multiple** tasks across multiple agents?" | **Participant** – subagents are workers controlled by the workflow. |

## 2. What belongs in the core (Agent Runtime)

Everything that executes a **single** agent turn:

| Building block | Role |
|---|---|
| `Step` | One model call + parallel tool execution |
| `ContextBuilder` / `Prepared` | Context of a turn |
| `ToolRuntime` / `ToolAuthorizer` | Tool execution |
| `PermissionEngine` | Authorization per turn |
| `CompactionService` | Context management |
| `Coordinator` (single-session) | Claim, doorbell, terminal event of **one** session |
| `Admission` / `Delivery` | Inbox of a session |
| `Recovery` (single-session) | Claim-based resumption |
| `ProviderAdapter` | LLM call |

### 2.1 Subagents in the core – only as helpers

The core provides the **primitives** so the agent can delegate:

| Building block | Role in the core |
|---|---|
| `SubagentTool` | `ToolDefinition` in the snapshot – the agent can call `subagent` |
| `SubagentRunner` | Execution primitive: `run(session, agent, prompt)` |
| `BackgroundTasks` | Primitive registry: task ID, status, result (in-memory) |
| `SubagentSessionRegistry` | Parent/child tracking |
| `PermissionGate` | Authorization of delegation |
| `ToolRuntime` | Hooks, decode, encode |

**The core knows nothing about:** dependencies, DAGs, tiers, compensations,
durable persistence, cross-session wake, quotas, versioning.

The core executes a `subagent` call and returns a result. Period.

## 3. What belongs in the workflow layer (top)

Orchestration of **multiple** agent turns:

| Building block | Role |
|---|---|
| `TaskNode` + `dependsOn` | Task model with dependencies |
| DAG scheduler | Ordering, parallelism, fan-out/fan-in |
| `TaskTier` (research/analyze/execute/saga) | Policy over agent profiles |
| `SagaRunner` + compensations | Multi-step transactions |
| `BackgroundTaskStore` (durable) | Persistence across restarts |
| `TaskResultDelivery` | Inbox-steer to parent |
| Task quota per session | Resource governance |
| Version/schema versioning | Lifecycle of definitions |
| Heartbeat (task/worker) | Observability across sessions |
| Scheduler/webhook triggers | External entry points |

### 3.1 Subagents in the workflow layer – as workers

Here subagents are not tools but **participants** in a plan:

- "Start research subagent A, then analysis subagent B, then execute C."
- "Run A and B in parallel, wait for both, then C."
- "If C fails, compensate B and A."

The workflow layer decides:
- **When** a subagent starts (dependencies).
- **Which tier** it uses (research/analyze/execute/saga).
- **Whether** it must be durable.
- **How** the result is forwarded (inbox delivery).

## 4. Communication between the layers

The workflow layer uses the core **only through ports**, never through internals:

```
┌─────────────────────────────────────────────────────┐
│ Workflow layer (runtime/workflow)                   │
│   TaskService, DAG-Scheduler, SagaRunner,           │
│   TaskStore, HeartbeatPublisher, Quota              │
│        │  Ports (SessionStore, Journal, Inbox,      │
│        │  ClaimStore, SessionWake, ToolRuntime)     │
└────────┼────────────────────────────────────────────┘
         │
┌────────┼────────────────────────────────────────────┐
│ Agent Runtime (engine core)                        │
│   Session, Coordinator, Step, ContextBuilder,       │
│   ToolRuntime, PermissionEngine, Compaction         │
└────────────────────────────────────────────────────┘
```

| Workflow action | Core port used |
|---|---|
| Task starts subagent | `SessionService.create` + `Coordinator.wake` |
| Task result to parent | `Admission.admit` + `SessionWake.wake` |
| Task claim (durable) | `ClaimStore.acquire/release` |
| Task events | `Journal.append` |
| Tool execution in task | `ToolRuntime` (via `Step`) |
| Permission in task | `PermissionEngine` |
| Heartbeat | `EventBus.publishLive` + Journal |
| Quota | Semaphore (runtime primitive) |

**Rule:** The core imports **nothing** from the workflow layer.
The dependency points only downward (Workflow → Core).

## 5. Allocation: where things live

| Concept | Core (Runtime) | Workflow layer |
|---|---|---|
| `SubagentTool` as tool definition | ✅ | ❌ (uses it only indirectly) |
| `SubagentRunner.run(session, agent, prompt)` | ✅ | ✅ (calls it) |
| `BackgroundTasks` (registry) | ✅ (primitive) | ✅ (controls lifecycle) |
| `SubagentSessionRegistry` | ✅ | ❌ |
| Task dependencies, DAG | ❌ | ✅ |
| Tier model | ❌ | ✅ |
| SAGA + compensations | ❌ | ✅ |
| Durable task persistence | ❌ | ✅ |
| Cross-session wake | ❌ | ✅ (uses `SessionWake`) |
| Task quota per session | ❌ | ✅ |
| Version/schema versioning | ❌ | ✅ |
| Heartbeat (execution, single-session) | ✅ | ❌ |
| Heartbeat (task/worker) | ❌ | ✅ |
| Permission check for subagent | ✅ | ❌ (delegates to core) |
| Tool snapshot filtering | ✅ | ❌ (delegates to core) |

## 6. Why not just one layer?

| Approach | Problem |
|---|---|
| **Core only** (subagents as tools, no workflow) | No dependencies, no SAGA, no durable tasks, no cross-session wake. The agent must orchestrate everything itself → complex, error-prone. |
| **Workflow only** (everything orchestrated) | Workflow would have to know about tool execution, permissions, context building, compaction → violates single responsibility, hard to test. |
| **Both (core + workflow)** | Clear separation: core = "one turn", workflow = "coordinate many turns". |

**Mnemonic:** The core provides the **primitive** "execute subagent X".
The workflow layer **composes** plans with dependencies, tiers and
compensations from it. Resilience and saga live **only** at the top.

## 7. Current state in QOC (mixed)

In the code the separation is not yet clean:

| Building block | Current location | Should be |
|---|---|---|
| `BackgroundTasks` | `runtime/tool/subagent/` | `runtime/workflow/` |
| `SubagentTool.executeBackground` | `runtime/tool/subagent/` | `runtime/workflow/` (calls `SubagentRunner`) |
| `TaskTool` | `runtime/tool/subagent/` | `runtime/workflow/` (or HTTP API) |
| `SubagentSessionRegistry` | `runtime/tool/subagent/` | `runtime/workflow/` |
| `Engine` (Quarkus) | orchestrates subagents + live events | Workflow layer uses `Engine` as core API |
| `MAX_CONCURRENT_SUBAGENTS` | `Engine.java:2249` | `TaskQuota` in workflow layer |

## 8. Migration steps

1. Move `BackgroundTasks` + `SubagentTool.executeBackground` + `TaskTool` +
   `SubagentSessionRegistry` out of `runtime/tool/subagent/` into
   `runtime/workflow/` (pure package rename).
2. Add `TaskNode` + `dependsOn`; `TaskService` as facade.
3. Implement DAG scheduler (`TaskScheduler`).
4. Introduce tier definitions as data.
5. Define `BackgroundTaskStore` port; memory and SQL adapters.
6. Cross-session wake via `Admission`/`SessionWake`.
7. SAGA + compensations (durable, journal events).
8. Heartbeat (`HeartbeatPublisher`) as workflow component.
9. Quota per session in `TaskService`.
10. ArchUnit rules for the layer boundary.

## 9. When does the separation pay off, when not?

| Criterion | Separation pays off | Separation not needed |
|---|---|---|
| **Scope** | Multi-session workflows, SAGA, durable tasks | Only single subagents, no DAG |
| **Testability** | Workflow tests against fake core | Simple subagent calls |
| **Scaling** | Workflow as own service | Single process |
| **Team** | Separate responsibility core/workflow | Small team |
| **Complexity** | Dependencies, compensations, versions | No dependencies |

**Recommendation for QOC:** The separation pays off because:
- The building blocks already exist and are only misplaced.
- The spec already describes a layering (Coordinator = single-session,
  workflow = multi-session).
- Durable tasks and SAGA require persistence the core should not know
  about.
- The hexagonal architecture fits: workflow is a `runtime.*` module that
  uses `core.*`, but not the other way around.

**If you only need simple subagents** (no DAG, no SAGA, no persistence),
the current structure is sufficient. The separation becomes relevant once
you need **dependencies, versions or compensations**.

## 10. References

- [`Core.md`](./Core.md) §2 (core scope), §8 (extraction boundary)
- [`Task-Orchestration.md`](./Task-Orchestration.md) (task dependencies, tiers, SAGA)
- [`Async-use.md`](./Async-use.md) (variants B/C, heartbeat, recovery)
- [`Storage-alternative.md`](./Storage-alternative.md) (persistence, journal as source of truth)
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/` (current building blocks to be moved)
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` (subagent wiring, live events)
- `qoc/docs/spec/session-engine/12-execution-model.md` (execution model)
- `qoc/docs/spec/session-engine/04-algorithms.md` (algorithms)
