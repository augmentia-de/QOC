# Runtime Plan Adaptation — Concept (Supervisor / Orchestrator)

> **Status:** Concept (2026-11-17), not implemented.  
> **Scope:** `workflow-runner` (Plan, Dispatch, Context) and `engine` (Run-Loop, Journal, Claims, Resilience).  
> **Starting point:** adapting a single node when an input requirement is missing.

---

## 1. Analysis of the Current Architecture

### 1.1 Roles

| Component | Responsibility | File |
|---|---|---|
| `WorkflowExecutor` | starts/resumes a run, snapshots the expanded definition | `workflow-runner/.../WorkflowExecutor.java` |
| `WorkflowStepProviderImpl` | deterministic navigation (`getNextNodeId`), Dispatch, Circuit-Breaker, Events | `.../WorkflowStepProviderImpl.java` |
| `DefaultNodeDispatcher` | executing node types, output contracts, template resolution | `.../DefaultNodeDispatcher.java` |
| `WorkflowContext` | `nodeOutputs`, `workflowState`, `currentNodeId`, `nextNode` override | `.../WorkflowContext.java` |
| `WorkflowDefinition` | immutable plan (Nodes, Edges, Agents, Schemas) | `.../model/WorkflowDefinition.java` |
| `QocEngine.runPrompt` | step loop, Paused/Failed/WorkflowDone, Retry-Policy | `engine/.../QocEngine.java` |
| `WorkflowRunJournal` | replay from journal, snapshot, resume | `.../WorkflowRunJournal.java` |

### 1.2 What already exists

1. **Dynamic override:** `WorkflowContext.state("nextNode")` allows `WorkflowStepProviderImpl` to override the next node (state machine). Used by `loop`, `guard`, `applyPostGuard`.
2. **Circuit-Breaker:** `recordNodeExecution` + `maxNodeRepetitions` prevents infinite loops.
3. **Output-Contracts:** `NodeContractValidator` + `validateOutputContract` (strict → Exception, non-strict → warning).
4. **Resume:** Journal replay → `WorkflowContext.restore`, `loadDefinition` via version/hash.
5. **Resilience:** `ErrorClassifier`, `RetryPolicy`, `TriageService` (LLM), `LoopDetector`, `WebhookAlerter`.
6. **SubWorkflowExpander:** static expansion of sub-workflows before the run.
7. **Input-Schema:** `WorkflowDefinition.inputSchema()` is validated at start.
8. **Child-Sessions:** Every agent node persists its child session ID in the journal (`WorkflowNodeCompleted.childSessionId`, `WorkflowRunSnapshot.nodeSessions`). This enables session requery as a repair strategy.

### 1.3 Gap

- A run works on **one** immutable snapshot. There is no mechanism to derive a plan during the run, validate it, and switch to it.
- On node failure, `WorkflowStepProviderImpl` propagates `StepResult.Failed` → `QocEngine` retries the same node up to `maxAttempts`, then aborts the run. There is no plan-level recovery.
- "Input requirement missing" is only detected at dispatch (strict template resolution throws `UnresolvedPlaceholderException` → Failed). No preflight check, no alternative route.
- The `nextNode` override is a flat signal, not a stack- or graph-based construct; re-entering the original plan is only possible manually.
- Resume loads the original definition; a plan adapted at runtime would be lost after a restart.

---

## 2. Goal & Invariants

**Goal:** A workflow has an original plan (immutable, versioned). In case of failure, a supervisor takes over, produces a **derived plan** for the next steps, and leads the run back to the original plan (Rejoin). The original plan is never mutated.

**Invariants (hard):**

1. `I-RPA-1` Original-plan immutability: `basePlanVersion` never changes; derived plans are new versions with lineage.
2. `I-RPA-2` Determinism: navigation remains deterministic; supervisor decisions are journaled as events and are replayable.
3. `I-RPA-3` Boundedness: every adaptation has a budget (max nodes, steps, tokens, time); exceeding it → abort/escalation.
4. `I-RPA-4` No silent skip: a required input requirement must not silently become `null` (strict).
5. `I-RPA-5` Completed-node protection: already completed nodes must not be redefined or skipped.
6. `I-RPA-6` Rejoin obligation: every adaptation plan must contain a defined rejoin anchor; without a rejoin it is not executed.
7. `I-RPA-7` Idempotency: resume after a crash continues the last accepted plan (no fallback to an older version).
8. `I-RPA-8` Validation before execution: derived plans pass through the same `GraphCompiler` as original plans.
9. `I-RPA-9` Blast radius before repair: before every repair, the affected downstream area (static dependency analysis) is computed and journaled; a repair without blast-radius computation is rejected.
10. `I-RPA-10` No silent auto-replay of side-effecting nodes: nodes with side effects (mcp_call, rest_call, agent with tools) are only re-executed automatically when idempotency or compensation is declared; otherwise HITL.

---

## 3. Core Abstractions

### 3.1 Plan & Lineage

```java
public record PlanRef(
    String planId,          // = workflowId
    String version,         // "1.0.0", "1.0.0+adapt-3"
    String parentVersion,   // null for Base
    PlanKind kind,          // BASE | ADAPTED
    String rejoinAnchor,    // Node-ID to return to (null for BASE)
    Instant createdAt
) {}

public enum PlanKind { BASE, ADAPTED }
```

- `WorkflowDefinition` remains unchanged; versioning lives in `PlanRef` + Journal.
- An adapted plan is a **copy** of the base definition plus insertions/patches, validated by `GraphCompiler`. The base stays referenceable.
- Lineage is recorded in the journal as a `PlanVersionChanged` event.

### 3.2 Requirement Model

```java
public record InputRequirement(
    String id,              // e.g. "req:nodeX:customer.id"
    String consumerNodeId,
    String ref,             // "${state.customer.id}", "initial.id", "upstream.output.id"
    RequirementKind kind,    // NODE_OUTPUT | STATE | INITIAL | LAST_RESULT | ARTIFACT
    boolean required,
    String expectedType,    // optional JSON-Schema type
    String producerNodeId   // optional: node that should deliver it
) {}

public enum RequirementKind { NODE_OUTPUT, STATE, INITIAL, LAST_RESULT, ARTIFACT }

public record RequirementGap(
    InputRequirement requirement,
    GapReason reason,
    GapSeverity severity,    // ERROR (required) | INFO (optional, not sent to supervisor)
    String evidence          // e.g. "node 'fetch-customer' not executed in this branch"
) {}

public enum GapSeverity { ERROR, INFO }

public enum GapReason {
    PRODUCER_NOT_EXECUTED,   // producer exists, but not on the path
    OUTPUT_MISSING,          // producer ran, but no output
    FIELD_MISSING,           // output present, field missing
    TYPE_MISMATCH,           // field present, wrong type
    CONTRACT_VIOLATION,      // output contract violated
    UNKNOWN_REFERENCE        // ref points to a non-existent node
}
```

### 3.3 Adaptation Plan

```java
public sealed interface PlanChange permits
    InsertNode, PatchNode, InsertEdge, RemoveEdge, SkipNode, Rejoin,
    RequerySession, ReplayFrom {}

public record InsertNode(String nodeId, WorkflowDefinition.Node node, String insertBefore) implements PlanChange {}
public record PatchNode(String nodeId, WorkflowDefinition.Node replacement, List<String> changedFields) implements PlanChange {}
public record InsertEdge(String edgeId, WorkflowDefinition.Edge edge) implements PlanChange {}
public record RemoveEdge(String edgeId) implements PlanChange {}
public record SkipNode(String nodeId, String reason) implements PlanChange {}
public record Rejoin(String anchorNodeId) implements PlanChange {}
public record RequerySession(
    String producerNodeId,       // node whose session is requeried
    String childSessionId,      // persisted child session ID
    String followupPrompt,      // follow-up prompt
    String targetRequirementId, // which requirement this satisfies
    String resultKey            // under which key the follow-up result is stored
) implements PlanChange {}
public record ReplayFrom(
    String originNodeId,        // first node that transitively consumed the missing value
    List<String> nodeIds,       // nodes to invalidate/re-execute (topologically sorted)
    String reason               // e.g. "stale downstream output after filling X"
) implements PlanChange {}

public record AdaptationPlan(
    String adaptationId,
    String triggerEvent,     // NodeFailed | RequirementGap | ContractViolation | ...
    String baseVersion,
    List<PlanChange> changes,
    String rejoinAnchor,     // mandatory (I-RPA-6)
    AdaptationBudget budget,
    String rationale,
    AdaptationSource source  // RULE | LLM_TRIAGE | HUMAN
) {}

public record AdaptationBudget(
    int maxInsertedNodes,
    int maxAdditionalSteps,
    int maxAdditionalTokens,
    java.time.Duration maxAdditionalTime
) {}
```

### 3.4 Supervisor

```java
public interface WorkflowSupervisor {
    /**
     * Returns an adaptation plan or "no adaptation" (Optional.empty()).
     * Must be deterministic/validatable; LLM suggestions are normalized
     * into an AdaptationPlan and then validated.
     */
    Optional<AdaptationPlan> propose(AdaptationContext ctx);
}

public record AdaptationContext(
    String runId,
    String workflowId,
    String currentPlanVersion,
    String blockedNodeId,
    GapReason gapReason,
    List<RequirementGap> gaps,
    WorkflowContext workflowContext,
    WorkflowDefinition currentPlan,
    Map<String, String> availableChildSessions,   // nodeId -> childSessionId
    List<String> completedNodeIds,
    Throwable failure,
    int adaptationCountSoFar
) {}
```

Implementations:
- `RuleBasedSupervisor`: deterministic rules (e.g. "requirement missing → catch up producer node if present").
- `LlmSupervisor`: uses a `TriageService`-like call, returns a JSON `AdaptationPlan`.
- `HumanSupervisor`: pauses the run (HITL) and waits for a manual plan.

### 3.5 Runtime Plan Container

```java
public final class RuntimePlan {
    private final WorkflowDefinition definition;   // currently active plan
    private final PlanRef ref;
    private final Set<String> injectedNodeIds;
    private final Set<String> patchedNodeIds;
    private final String rejoinAnchor;
    // ...
    public WorkflowDefinition definition() { return definition; }
    public PlanRef ref() { return ref; }
}
```

`WorkflowStepProviderImpl` holds a `RuntimePlan` instead of just `def` (initially Base). Navigation uses `runtimePlan.definition()`. Adaptation swaps the `RuntimePlan` atomically (volatile + claim protection).

---

## 4. Runtime Flow

### 4.1 Normal Path (unchanged)

```
getNextNodeId(basePlan, ctx) → dispatch node → success → setCurrentNodeId → ...
```

### 4.2 Preflight Before Dispatch (new)

Before `dispatcher.dispatch(node, ...)`, `WorkflowStepProviderImpl` checks:

1. `RequirementChecker.check(node, def, ctx)` → `List<RequirementGap>`.
2. If no gaps → dispatch as before.
3. If gaps and node is `required`:
   - Build `AdaptationContext`.
   - Call `supervisor.propose(ctx)`.
   - If a plan exists and is valid → apply adaptation → set `nextNode` to the first inserted node (or rejoin if nothing to insert) → journal → continue.
   - If no plan / invalid → `StepResult.Failed` (with gap details) → Engine retry/escalation.
4. If gaps and node is `optional` (non-required): mark `state("degraded:<node>") = gaps`, dispatch anyway (legacy passthrough, but logged).

### 4.3 Applying an Adaptation

```
apply(plan):
  1. Validation (GraphCompiler + invariants I-RPA-3/5/6/8)
  2. Build new RuntimePlan (derived version)
  3. Journal: PlanVersionChanged(base→derived), AdaptationApplied(adaptationId, changes)
  4. ctx.state("nextNode") = firstInsertedNode (or rejoinAnchor)
  5. ctx.state("_activeAdaptation") = adaptationId
  6. ctx.state("_adaptationBudget") = remaining
```

Navigation within the adaptation uses the derived definition. After reaching the `rejoinAnchor`:
- `ctx.state("nextNode")` is set to `null` (normal path).
- Journal: `AdaptationRejoined(adaptationId, anchorNodeId)`.
- `_activeAdaptation` is removed.
- Continue on the original plan from the anchor.

### 4.4 Return to the Original Plan

The rejoin anchor is a node ID present in **both** plans (completed-node protection). On reaching it:
- `currentPlanVersion` is marked as "rejoined" in the journal.
- `RuntimePlan` is reset to the base version (or stays derived, but without active adaptation; the derived definition still contains the base nodes, so the path is identical). Recommendation: keep RuntimePlan derived (for audit), but `_activeAdaptation=null`; navigation keeps using the derived definition, which contains the base as a subgraph. This simplifies resume.

### 4.5 Budget & Abort

- Every adaptation counts against `adaptationCountSoFar`. Max `MAX_ADAPTATIONS_PER_RUN` (e.g. 5).
- Budget exceeded → `StepResult.Failed` with `FailureKind` + Webhook/HITL.
- Circuit-Breaker (`maxNodeRepetitions`) stays active; adaptations may not bypass it.

---

## 5. First Slice: "Input Requirement Missing"

### 5.0 Requiredness: when is a missing value a gap?

**Principle:** Not every `null` value is an error. Many inputs are optional, and `null` is a legitimate result. The supervisor may only intervene on **explicitly required** inputs; otherwise it generates noise and unnecessary repairs.

**Rules:**

1. **Default = optional.** `InputRequirement.required` defaults to `false`. A missing optional value produces **no** gap; it is passed through as `null` (with a `debug` log).
2. **Explicit declaration takes precedence.** `NodeData.inputRequirements` can set `required: true/false` per ref. Only `required=true` becomes a gap.
3. **Suffix convention in templates:** `${node.output.field!}` (with `!`) marks a field as required; without `!` it is optional. Analogously for `contextRefs`: `"node.field!"`.
4. **Schema-based derivation (optional):** If an upstream node declares an `outputContract` with `required: [...]` and the downstream consumes exactly that field, it is treated as required. Without an output contract → optional.
5. **Node-level strictness:** `NodeData.strict == true` makes **all** referenced inputs of the node required (analogous to the existing strict behavior for output contracts). Non-strict (default) → optional.
6. **Severity:** A required gap is `ERROR` (blocks dispatch); an optional missing value is `INFO` (dispatch proceeds, value is `null`).

**Consequence for RequirementChecker:**

```java
for (InputRequirement r : requirements) {
    if (!r.required()) {
        if (!isSatisfied(r, ctx)) log.debug("optional input missing: {}", r.ref());
        continue; // no gap
    }
    if (!isSatisfied(r, ctx)) gaps.add(new RequirementGap(r, ..., GapSeverity.ERROR));
}
```

Optionally missing values are **not** forwarded to the supervisor.

### 5.1 Starting Situation

Node `analyze-order` has prompt:
```
Analysiere Bestellung ${order-fetch.output.orderId} ...
```
or `contextRefs: ["order-fetch.orderId"]`.

`order-fetch` was not executed in another branch (e.g. condition `status=='VIP'` was false) → output missing → strict template resolution throws `UnresolvedPlaceholderException` → node failed.

### 5.2 Detection (RequirementChecker)

```java
public final class RequirementChecker {
    public static List<RequirementGap> check(WorkflowDefinition.Node node,
                                            WorkflowDefinition def,
                                            WorkflowContext ctx) {
        List<RequirementGap> gaps = new ArrayList<>();
        // 1. Explicit requirements (new field NodeData.inputRequirements)
        for (InputRequirement r : node.data().inputRequirements()) {
            if (!isSatisfied(r, ctx)) gaps.add(new RequirementGap(r, reason(r, ctx), evidence(r, ctx)));
        }
        // 2. Implicit: template placeholders in title/defaultTarget/mcpArgs
        for (String ref : extractRefs(node)) {
            // ... as above, with required=true
        }
        // 3. contextRefs
        for (String cr : node.data().contextRefs()) {
            if (ContextRefResolver.resolve(ctx, cr) == null) gaps.add(...);
        }
        return gaps;
    }
}
```

### 5.3 Supervisor Strategies (rule-based, in this order)

0. **Session-Requery (new, preferred):** If a producer node exists **and** a persisted child session ID for it is available (`WorkflowRunSnapshot.nodeSessions` / metadata in the context), and the gap is `FIELD_MISSING`, `OUTPUT_MISSING`, `CONTRACT_VIOLATION`, or `TYPE_MISMATCH`:
   - The supervisor produces a `RequerySession` change: the existing child session is resumed with a follow-up prompt (e.g. *"Your previous output was: … . Field X is missing. Please provide X as JSON."*).
   - The follow-up result is stored under `resultKey` (e.g. `producerNodeId + ".followup"`); the blocked node is rewired to it (`contextRefs`/template on `resultKey`).
   - **Advantages:** no restart of the producer, context (reasoning) retained, minimal token consumption, no graph mutation.
   - **Limitation:** only useful if the child session is still resumable and the producer agent can deliver the value; for `PRODUCER_NOT_EXECUTED` (producer never ran) not applicable → fallback to strategy 1.
   - **Journal:** `SessionFollowupRequested`, `SessionFollowupCompleted` (with child session ID and tokens).

1. **Catch up producer (Reroute):** If `producerNodeId` is known and present in the definition, but not in `completedNodeIds`:
   - Adaptation plan: `InsertEdge`? No — instead `ctx.state("nextNode") = producerNodeId`, and after its success back to the blocked node. Implemented as a mini-plan with two virtual "Guard" nodes? Simpler: the supervisor sets `nextNode = producerNodeId` and records `state("_pendingNode") = blockedNodeId`. `WorkflowStepProviderImpl` checks `_pendingNode` after a node completes: if set and node == producer → `nextNode = _pendingNode`, `_pendingNode = null`. This uses the existing override mechanism, no graph mutation.
   - Advantage: minimal intervention, no new plan needed.
   - Disadvantage: only linear; no complex repairs.

2. **Adapted Plan (InsertNode):** If no producer exists, but an MCP tool/agent can deliver the value:
   - The supervisor creates a `fetch-*` node (e.g. `mcp_call` or `agent`) and inserts it before the blocked node. Rejoin anchor = blocked node.
   - Validation by `GraphCompiler`.

3. **Adjust node (PatchNode):** If the requirement can be made optional:
   - Change the prompt so the missing information is explicitly treated as "not available" (e.g. `"... (if orderId is missing, ask)"`).
   - Or re-point `contextRefs` to available sources.
   - Only allowed when `required=false` or the supervisor classifies it as `DEGRADABLE`.

4. **HITL (HumanSupervisor):** If no automatic repair is possible or the budget is exhausted → `NodeResult.paused(approvalId, ...)` with diagnosis; resume after human input.

### 5.4 Example Adaptation Plan (Reroute + InsertNode)

```json
{
  "adaptationId": "adapt-1",
  "triggerEvent": "RequirementGap:PRODUCER_NOT_EXECUTED",
  "baseVersion": "1.0.0",
  "changes": [
    { "type": "InsertNode", "nodeId": "fetch-order", "insertBefore": "analyze-order" },
    { "type": "InsertEdge", "edge": { "source": "fetch-order", "target": "analyze-order" } }
  ],
  "rejoinAnchor": "analyze-order",
  "budget": { "maxInsertedNodes": 1, "maxAdditionalSteps": 3, "maxAdditionalTokens": 4000, "maxAdditionalTime": "PT2M" },
  "rationale": "order-fetch was skipped in the VIP branch; run it afterwards",
  "source": "RULE"
}
```

---

### 5.5 Divergence, Blast Radius and Deterministic Repair

**Problem:** Missing data need not occur where it is produced. A field that was missing 5 steps earlier may have led to divergent results in the intermediate steps. Simply "filling in the field" at the source leaves that divergence in place.

**Deterministic preparatory work (no LLM):**

1. **Static dependency analysis (compile time):** For each node, compute which upstream fields it consumes (from templates `${...}`, `contextRefs`, `mcpArgs`). Result: `DependencyGraph` (consumer → set of (producer, field)).
   - Implementation: static evaluation analogous to `TemplateResolver.findProblems` / `ContextRefResolver.findProblems`, persisted in the plan snapshot.
2. **Blast-radius computation (runtime):** When a gap for `(producer P, field X)` is detected at node `F`, the blast radius = all nodes that transitively depend on `(P, X)` and have already executed (topologically sorted, P included). This is deterministic.
3. **Repair mode (policy, deterministic from metadata):**
   - `PATCH_ONLY`: If no downstream node actually consumed the missing field (blast radius = {P}), filling it in at P is sufficient. Downstream stays unchanged.
   - `REPLAY_FROM(origin)`: If downstream nodes consumed the field, all nodes in the blast radius (except P, whose output is patched) are invalidated and re-executed in topological order (`ReplayFrom` change). Origin = first downstream node that consumed the field.
   - `COMPENSATE_AND_REPLAY`: If nodes in the blast radius have side effects and are not idempotent, their compensations run first (SAGA, `docs/engineering/SAGA.md`), then replay.
   - `ESCALATE`: If compensation is not declared or the budget is exhausted → HITL.

**Determinism of the repair:**

- Gap detection, blast radius, and mode selection are deterministic (no LLM).
- The *contents* of a session requery or insert node are LLM-generated, but the *step* (which node, which prompt template, which output contract) is fixed by the adaptation plan and validated.
- Re-execution order is topologically deterministic.
- Journaling makes every step replayable: original completion events remain; re-execution produces `NodeInvalidated` + `NodeReexecuted` events with new output. Resume uses the last output per node.

**Idempotency/Side-Effects:**

- `NodeData` optionally gains `idempotencyKey` (template, e.g. `${node.output.id}`) and `compensation` (agent/prompt or MCP tool).
- The supervisor checks before auto-replay: `idempotent || compensation != null`; otherwise `ESCALATE`.
- Pure nodes (`llm_eval`, `llm_summarize`, `decision`, `compact`) are idempotent by default.

**Consistency after repair:**

- After `REPLAY_FROM`, the rejoin anchor is set to the last node of the blast radius, not to the originally blocked node, so no stale downstream outputs remain.
- The original plan stays unchanged; the derived version contains the invalidated/re-executed nodes as a new execution generation (marked via `executionGeneration` in `WorkflowContext`).

## 6. Journal Events (new)

```java
sealed interface SessionEvent permits ...,
    PlanVersionChanged, AdaptationProposed, AdaptationApplied,
    AdaptationRejoined, AdaptationAbandoned, RequirementGapDetected {}

record PlanVersionChanged(SessionId session, String runId, String fromVersion, String toVersion,
                          String reason, Instant time) implements SessionEvent {}
record AdaptationProposed(SessionId session, String runId, String adaptationId,
                         String trigger, String source, String rationale, Instant time) {}
record AdaptationApplied(SessionId session, String runId, String adaptationId,
                        List<String> changedNodeIds, String rejoinAnchor, Instant time) {}
record AdaptationRejoined(SessionId session, String runId, String adaptationId,
                          String anchorNodeId, Instant time) {}
record AdaptationAbandoned(SessionId session, String runId, String adaptationId,
                           String reason, Instant time) {}
record RequirementGapDetected(SessionId session, String runId, String nodeId,
                             List<String> gapRefs, String severity, Instant time) {}
record SessionFollowupRequested(SessionId session, String runId, String adaptationId,
                               String producerNodeId, String childSessionId,
                               String followupPrompt, Instant time) {}
record SessionFollowupCompleted(SessionId session, String runId, String adaptationId,
                               String producerNodeId, String childSessionId,
                               String resultKey, Object result, Instant time) {}
record NodeInvalidated(SessionId session, String runId, String adaptationId,
                      String nodeId, String reason, int executionGeneration, Instant time) {}
record NodeReexecuted(SessionId session, String runId, String adaptationId,
                     String nodeId, Object newOutput, int executionGeneration, Instant time) {}
```

Resume uses the last `PlanVersionChanged` version; without such events → base version (compatible).

---

## 7. Validation & Safety Gates

Before applying an `AdaptationPlan`:

1. `GraphCompiler.validate(derivedDefinition)` (syntax, DAG, invariants).
2. `PlanValidator` (new) checks:
   - `rejoinAnchor` exists in base and derived.
   - No completed node mutated/removed.
   - `maxInsertedNodes`/budget respected.
   - No cycles introduced by insertions.
   - No new agent refs without RBAC check (`agentAllowed`).
   - No new MCP servers/tools without allowlist.
3. On failure → `AdaptationAbandoned` + `StepResult.Failed` (no partial apply).

---

## 8. Resume & Replay

- `WorkflowRunJournal.load` additionally reads the last active plan version and `_activeAdaptation`.
- `WorkflowExecutor.resume` loads the derived definition (`store.version`/`findByHash` as today) — works because adaptations are snapshotted as new versions.
- `WorkflowContext.restore` is extended with `_activeAdaptation`, `_pendingNode`, `_adaptationBudget` (analogous to `_nodeExecutionCounts`).

---

## 9. Relationship to Engine Resilience

| Layer | Responsible for | Mechanism |
|---|---|---|
| Engine (`QocEngine`) | transient provider errors, timeouts, loops | RetryPolicy, ErrorClassifier |
| Workflow-Supervisor | plan-level errors, missing inputs, contract violations | AdaptationPlan, Rejoin |

Integration: `WorkflowStepProviderImpl` catches node failures. If `ErrorClassifier` classifies the error as `CORRECTABLE` and the supervisor returns a plan → adaptation instead of engine retry. Otherwise → `StepResult.Failed` → engine retry as before. After engine retries are exhausted → supervisor escalation (HITL/Webhook).

---

## 10. API & UI

- `WorkflowExecution` gains `planVersion`, `adaptationCount`, `lastAdaptationId`.
- HTTP: `GET /run/{id}/plan` (current + base version + lineage), `POST /run/{id}/adapt` (manual adaptation plan, approval required).
- UI: "Adapted (n)" badge on the run; diff view base vs. derived; rejoin marker.

---

## 11. Test Plan (first slice)

1. `RequirementCheckerTest`: missing producer, missing field, type mismatch, contract violation.
2. `DependencyGraphTest`: static extraction of consumed fields; transitive blast-radius computation.
3. `SessionRequerySupervisorTest`: existing child session → requery plan; no producer → fallback.
4. `RuleBasedSupervisorTest`: reroute on missing producer; insert node on missing MCP value; HITL on unknown ref.
5. `PlanValidatorTest`: completed-node mutation rejected; cycle rejected; missing rejoin rejected.
6. `WorkflowStepProviderImplAdaptationTest`: preflight → adaptation → execution of inserted node → rejoin → continuation of original plan; resume after simulated crash.
7. `WorkflowRunJournalTest`: PlanVersionChanged/Adaptation* events replay → correct state.

---

## 12. Phases (Implementation)

- **Phase A (minimal, 2-3 days):** RequirementChecker + **Session-Requery** (preferred when a child session exists) + reroute fallback. Journal events for gap, requery, reroute. Tests.
- **Phase B:** `AdaptationPlan` + `RuntimePlan` + `PlanValidator` + InsertNode/PatchNode. Journal versioning. Resume.
- **Phase C:** LlmSupervisor via TriageService; HITL integration; UI/API.
- **Phase D:** Budgets, metrics, hardening.

---

## 13. Non-Goals (for now)

- No mutation of the original plan at runtime (only derived versions).
- No automatic deletion of nodes.
- No parallel adaptations (serial, claim-protected).
- No UI-editor mutation during a run (read-only view only).

---

## 14. Open Decisions

1. Should `inputRequirements` be an explicit field in `NodeData` (new field) or purely derived implicitly from templates? Recommendation: both, explicit takes precedence.
2. Reroute vs. InsertNode as default for a missing producer: recommendation Reroute (minimal intervention).
3. How many adaptations per run? Recommendation 5, configurable.
4. Should the LlmSupervisor use the same provider as the run or a faster one? Recommendation: faster triage provider (`ENGINE_TRIAGE_MODEL`).

---

## 15. References

- `docs/engineering/ReAct-Workflow.md` (static navigation, drawback "rigid")
- `docs/resilience.md` (phases 0-4, triage)
- `docs/engineering/Task-Orchestration.md` (DAG, tiers)
- `docs/engineering/SAGA.md` (compensations, journal)
- `docs/engineering/Workflow-SDLC.md` (orchestrator as WorkflowDefinition)
- `engine/runtime/resilience/*` (ErrorClassifier, TriageService, RetryPolicy)
- `workflow-runner/runner/WorkflowStepProviderImpl.java` (`nextNode` override, circuit breaker)
- `workflow-runner/runner/DefaultNodeDispatcher.java` (`validateOutputContract`, strict)
- `workflow-runner/runner/TemplateResolver.java` (strict resolution)