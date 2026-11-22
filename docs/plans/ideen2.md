# Ideas2 – Structure, Benefit and Implementation Order

> **Status:** Structuring/planning document (2026-11-22) — Bundles existing state, benefit, priorities. Not an implementation plan.
> **Basis:** `docs/plans/next/runtime-plan-adaptation.md`, `docs/plans/next/goal-driven-planning-comparison.md`, `docs/plans/next/ideen-summary.md`, `docs/plans/next/ideen.txt`.  
> **Goal:** Bundle existing state, benefit, priorities, order and small tests in one place.

---

## 1. What we have (existing codebase)

| Building block | Status | Relevance to the ideas |
|---|---|---|
| `WorkflowDefinition` (immutable Nodes/Edges, `inputSchema`) | present | Base plan that an adaptation must never mutate |
| `WorkflowStepProviderImpl` | present | deterministic navigation, `nextNode` override, circuit breaker |
| `WorkflowContext` | present | `nodeOutputs`, `workflowState`, `currentNodeId`, `lastResult` |
| `WorkflowRunJournal` | present | Replay/Resume, `nodeSessions` (child session IDs) |
| `DefaultNodeDispatcher` | present | Node execution, output contracts, template resolution |
| `TemplateResolver` / `ContextRefResolver` | present | `findProblems` for static reference validation |
| `GraphCompiler` | present | Plan validation (DAG, references) |
| `NodeContractValidator` | present | Output contract validation |
| `ErrorClassifier`, `RetryPolicy`, `TriageService`, `LoopDetector`, `WebhookAlerter` | present | Resilience at engine level |
| `SubWorkflowExpander`, HITL pause | present | static expansion, pausing |
| 34 test classes in `workflow-runner` | present | good basis for regression tests |
| `InputRequirement`, `RequirementChecker` | **missing** | Foundation for preflight |
| `AdaptationPlan`, `RuntimePlan`, `PlanValidator`, `PlanRef` | **missing** | Runtime adaptation |
| `WorkflowSupervisor` implementations | **missing** | Decision logic |
| Blast radius / Replay / SAGA invalidation | **missing** | Correctness after upstream correction |
| `NodeGoal` / LLM judge | **missing** (only `verifyOnStop` for tool checks) | Domain-level validation |
| Goal→Plan generation | **missing** | Initial plan generation from a goal |
| Isomorphic model (Workflow = context) | **partial** | long-term simplification |

---

## 2. Where it helps and what matters

| Idea | Benefit | Effort/Risk | Priority |
|---|---|---|---|
| **RequirementChecker + Preflight** | Detect errors early and in a structured way instead of later `UnresolvedPlaceholderException` | low, no behavior change in report mode | **P0** |
| **Session-Requery** | Request a missing field without a full re-run; uses existing child sessions | low–medium, no graph intervention | **P0** |
| **Reroute (catch up producer)** | Execute a missing producer afterwards; uses the existing `nextNode` override | low, linear | **P1** |
| **AdaptationPlan + PlanValidator + RuntimePlan** | Real plan adaptation (insert/patch) with validation | medium–high | **P1** |
| **Blast radius + Replay + SAGA** | Prevents stale downstream results after an upstream correction | high | **P2** |
| **NodeGoal / LLM judge** | Domain quality instead of schema only | medium | **P2** |
| **LLM supervisor + HITL** | Autonomous repair with escalation | medium | **P3** |
| **Goal→Plan (Phase 0)** | Initial workflow generation from current state + goal | high | **P3** |
| **Isomorphic model** | No mapping hell, one tree for workflow + context | very high (refactor) | **P4** |

### The three most important principles

1. **Observe first, repair later.** The RequirementChecker in report mode delivers data without changing behavior.
2. **Base plan stays immutable.** Adaptations are derived plans with lineage; no silent mutation.
3. **Cheap before expensive.** Code check → rules → LLM judge; no LLM where deterministic validation suffices.

### The three biggest pitfalls

- Auto-replay of side-effecting nodes without idempotency/compensation.
- Adaptation without a rejoin anchor → run hangs or jumps incorrectly.
- Token bloat from the entire tree in the prompt (subagent purging, selective scoping).

---

## 3. Order (recommended roadmap)

### Phase 0 – Secure the baseline (small)

- Feature flag `ENGINE_ADAPTATION_ENABLED` (default `false`).
- Regression tests for existing navigation, `nextNode` override, circuit breaker, resume.

### Phase A – Observe + minimally repair (MVP, recommended first)

1. **A1: Requirement model + checker (report-only).** Detect gaps, log them, take no action.
2. **A2: Preflight.** Required gap → `Failed` with gap details; optional gap → `degraded:<node>` + continue.
3. **A3: Session requery.** If a child session exists → follow-up question; result stored under `resultKey`.
4. **A4: Reroute.** Catch up the producer via `nextNode` + `_pendingNode`; then return to the blocked node.

**Why first:** high benefit, no graph rebuild, uses existing mechanisms.

### Phase B – Plan adaptation

5. **B1: `AdaptationPlan` + `PlanValidator`** (model + validation only, no execution yet).
6. **B2: `RuntimePlan` + Apply (InsertNode/PatchNode) + Rejoin.**
7. **B3: Journal events + Resume** (`PlanVersionChanged`, `AdaptationApplied`, `AdaptationRejoined`).

### Phase C – Correctness after upstream correction

8. **C1: DependencyGraph + Blast radius.**
9. **C2: Replay/SAGA + idempotency gates** (`PATCH_ONLY`, `REPLAY_FROM`, `COMPENSATE_AND_REPLAY`, `ESCALATE`).

### Phase D – Autonomy + quality

10. **D1: LLM supervisor + HITL.**
11. **D2: `NodeGoal` + LLM judge** (Funnel: schema → rules → judge).

### Phase E – Goal-driven planning

12. **E1: Tool registry (MCP/OpenAPI) + goal→DAG generation.**
13. **E2: Self-healing loop for invalid plans; GraphCompiler validation.**

### Phase F – Isomorphic model

14. **F1: Check whether workflow context and agent context can already be aligned.**
15. **F2: Long-term unification (only if the benefit justifies the refactor).**

---

## 4. Small, meaningful tests (per phase)

### Phase 0

- `WorkflowStepProviderImplTest`: Navigation with multiple outgoing edges, `nextNode` override, circuit breaker.
- `WorkflowRunJournalTest`: Resume after a simulated crash.

### Phase A1 – RequirementChecker

- Optional missing → **no** gap.
- Required missing → gap `ERROR`.
- `PRODUCER_NOT_EXECUTED`, `OUTPUT_MISSING`, `FIELD_MISSING`, `TYPE_MISMATCH`, `CONTRACT_VIOLATION`, `UNKNOWN_REFERENCE`.
- `!` suffix in template → required; without `!` → optional.
- `strict=true` → all refs required.

### Phase A2 – Preflight

- No gaps → dispatch unchanged.
- Required gap + no supervisor → `Failed` with details (no silent skip).
- Optional gap → dispatch, `state("degraded:<node>")` set.

### Phase A3 – Session-Requery

- Child session present → `RequerySession` change.
- No child session → fallback (no requery).
- Result lands under `resultKey`; blocked node is rewired.
- Journal: `SessionFollowupRequested` / `SessionFollowupCompleted`.

### Phase A4 – Reroute

- Producer exists, not executed → `nextNode=producer`, then back via `_pendingNode`.
- Producer already executed → no reroute.
- Circuit breaker stays active; completed node is not redefined.

### Phase B1 – PlanValidator

- Rejoin anchor exists in base **and** derived.
- Mutating a completed node → rejected.
- Cycle through insertion → rejected.
- Budget exceeded → rejected.
- New agent/MCP without allowlist → rejected.

### Phase B2 – RuntimePlan

- Base plan unchanged after adaptation.
- Derived plan contains the inserted node.
- Rejoin sets `_activeAdaptation=null`.
- Resume loads the last accepted version.

### Phase C1/C2 – Blast radius

- No consumer → `PATCH_ONLY`.
- Consumer present → `REPLAY_FROM`.
- Side-effect node without idempotency → `ESCALATE`.
- Transitive dependency is detected correctly.

### Phase D2 – NodeGoal

- Code rule pass/fail.
- LLM judge pass/fail (stub, no live LLM).
- Funnel: schema error → **no** LLM call.

### Phase E1 – Goal→Plan

- Valid plan → `GraphCompiler` green.
- Invalid JSON → self-healing retry.
- Invalid `targetNodeId` → retry.
- Max retries reached → clean error.

---

## 5. Recommendation for getting started

**MVP = Phase A1 + A2 + A3** (report-only checker, preflight, session-requery).  
This is small, testable, uses existing child sessions and does not change the base plan. Reroute (A4) immediately after.

**Not first:** isomorphic model and goal→plan. Both are large refactors and should only be tackled after a stable runtime adaptation.

### Open decisions before A1

1. `RequirementChecker` in `workflow-runner` (knows definition + context) – recommendation: yes.
2. Explicit `inputRequirements` vs. implicit from templates – recommendation: both, explicit wins.
3. Report-only default vs. direct preflight – recommendation: report-only, then flag.
4. Max adaptations per run – recommendation: 5.

---

## 6. References

- `docs/plans/next/runtime-plan-adaptation.md`
- `docs/plans/next/goal-driven-planning-comparison.md`
- `docs/plans/next/ideen-summary.md`
- `docs/plans/next/ideen.txt`
- `docs/engineering/ReAct-Workflow.md`
- `docs/resilience.md`
- `docs/engineering/Task-Orchestration.md`
- `docs/engineering/SAGA.md`
