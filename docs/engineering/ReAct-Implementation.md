# ReAct Implementation — Step-by-Step Implementation Plan

**Status:** Plan (2026-11-11) · Reference: `ReAct-Workflow.md` (architecture), `ReAct.txt` (original conversation)
**Goal:** Incrementally implement the hybrid engine in QOC — deterministic path control + LLM-based agent nodes, testable in isolated steps.

---

## Principle

Every step must be **implementable and testable in isolation**, without the subsequent steps already existing. The order follows data dependencies: whoever provides data must exist first.

---

## Step 0 — Prerequisite: keep existing tests green

**What:** `mvn test` in `workflow-runner` must be green (TopologicalSort, ConditionEvaluator, WorkflowExecutor, WorkflowStore).

**Test:** Existing unit tests + E2E script `scripts/e2e-workflow-test.sh`.

**Why first:** Every subsequent step builds on this; regressions must be visible immediately.

---

## Step 1 — Extend `WorkflowDefinition.Edge` with `condition`

**What:** Optional `condition` field in the `Edge` record (analogous to `NodeData.branches`, but at the edge level).

```java
public record Edge(String id, String source, String target, String type, String condition) {
    public Edge {
        if (id == null) id = "";
        if (source == null) source = "";
        if (target == null) target = "";
        if (type == null) type = "default";
        if (condition == null) condition = "";
    }
}
```

**Why first:** Pure data-model change, no logic. The backend reads/writes the field. The frontend can use it later. No dependency on other steps.

**Testable:** Unit test: deserialize JSON with a `condition` field, verify the field is set. Round-trip: serialize → deserialize → compare.

**Effort:** Very low. **Risk:** Minimal (additive field, `@JsonIgnoreProperties`).

---

## Step 2 — Introduce `WorkflowContext` (without logic changes)

**What:** New class `WorkflowContext` in the `workflowrunner.runner` package:

```java
public class WorkflowContext {
    // Structured outputs per node ID (for data piping and conditions)
    private final Map<String, Object> nodeOutputs = new HashMap<>();
    // Global state variables (e.g. lastStatus, lastResult, forkResults)
    private final Map<String, Object> workflowState = new HashMap<>();
    // Current position in the React-Flow graph
    private String currentNodeId;

    public void setNodeOutput(String nodeId, Object output) { nodeOutputs.put(nodeId, output); }
    public Object getNodeOutput(String nodeId) { return nodeOutputs.get(nodeId); }
    public void setState(String key, Object val) { workflowState.put(key, val); }
    public Object getState(String key) { return workflowState.get(key); }
    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String id) { this.currentNodeId = id; }
    public Map<String, Object> getNodeOutputs() { return nodeOutputs; }
}
```

**Note on chat history:** `WorkflowContext` contains **no** chat history (`List<Message>`). Reason: every agent node calls `QocEngine.runPrompt()`, which internally uses its own `ConversationStore` (DB-backed) and `ContextBuilder`. Chat history is managed there, not in the workflow context. `WorkflowContext` holds only workflow-specific state (node outputs, global variables, graph position).

**Why now:** Used by steps 3–7. Must exist before `nodeResults` is refactored.

**Testable:** Unit test: set/read outputs, set/read state, `null` handling. No dependency on WorkflowExecutor.

**Effort:** Low. **Risk:** Minimal.

---

## Step 3 — Use `EngineResult.structured()` (already available)

**What:** `QocEngine.runPrompt()` already returns `EngineResult` with a `structured` field (`Option<JsonValue>`). This field must be used in `NodeDispatcher` to pass structured outputs to `WorkflowContext`. It does **not** need to be reimplemented — only used.

```java
// EngineResult.java (already present in engine module)
public record EngineResult(
    String text,
    String finish,
    long inputTokens,
    long outputTokens,
    boolean failed,
    String errorMessage,
    boolean retryable,
    boolean paused,
    String approvalId,
    Option<JsonValue> structured   // ALREADY PRESENT
) { ... }
```

`JsonValue` is a sealed interface: `Obj(Map<String, JsonValue>)`, `Arr(List<JsonValue>)`, `Str(String)`, `Num(BigDecimal)`, `Bool(boolean)`, `Null()`.

When `AgentConfig.jsonOutput == true` and the LLM returns a valid JSON response, it is set as `JsonValue.Obj` in `structured`. Otherwise `Option.none()`.

**Conversion to Map:** In `NodeDispatcher`, `Option<JsonValue>` must be converted to `Map<String, Object>`:

```java
private Map<String, Object> toMap(Option<JsonValue> structured) {
    if (structured.isEmpty()) return null;
    JsonValue val = structured.orElseThrow();
    if (val instanceof JsonValue.Obj obj) {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, JsonValue> e : obj.values().entrySet()) {
            map.put(e.getKey(), toJavaObject(e.getValue()));
        }
        return map;
    }
    return null;
}

private Object toJavaObject(JsonValue val) {
    return switch (val) {
        case JsonValue.Str s -> s.value();
        case JsonValue.Num n -> n.value();
        case JsonValue.Bool b -> b.value();
        case JsonValue.Null n -> null;
        case JsonValue.Arr a -> a.values().stream().map(this::toJavaObject).toList();
        case JsonValue.Obj o -> {
            Map<String, Object> m = new HashMap<>();
            for (Map.Entry<String, JsonValue> e : o.values().entrySet()) {
                m.put(e.getKey(), toJavaObject(e.getValue()));
            }
            yield m;
        }
    };
}
```

**Why now:** Prerequisite for step 4 (agent node returns structured output). Can be implemented and tested independently of the WorkflowExecutor.

**Testable:**
- Unit test with mock provider: LLM returns `{"status":"SUCCESS","data":"..."}` → `structured()` contains `JsonValue.Obj`, conversion yields Map.
- LLM returns invalid JSON → `structured()` is `Option.none()`, `.text()` contains the raw response.
- `jsonOutput=false` → `structured()` is `Option.none()`.

**Effort:** Medium. **Risk:** Medium (provider adapter must support JSON mode; fallback on parse failure).

**Dependency:** None (`QocEngine` is independent of `WorkflowExecutor`).

---

## Step 4 — Switch `WorkflowExecutor` to `WorkflowContext` (String → Object)

**What:** `nodeResults: Map<String, String>` → `nodeResults: Map<String, Object>` (or `Map<String, WorkflowNodeResult>`). The agent node stores not only `.text()` but also `.structured()` (via `toMap()`) in the context.

```java
// New record
public record NodeResult(String text, Map<String, Object> structured) {
    public String text() { return text; }
    public Object get(String key) { return structured != null ? structured.get(key) : null; }
}
```

Agent node:
```java
EngineResult result = engine.runPrompt(prefixedName, nodeInput, config);
Map<String, Object> structured = toMap(result.structured());
NodeResult nodeResult = new NodeResult(result.text(), structured);
nodeResults.put(nodeId, nodeResult);
ctx.setNodeOutput(nodeId, structured != null ? structured : result.text());
```

**Why now:** Builds on steps 2 + 3. Makes structured outputs available in the workflow.

**Testable:**
- Existing E2E test must still be green (text path unchanged).
- New unit test: agent node with `jsonOutput=true` → `nodeResults.get(nodeId)` is a `NodeResult` with a `.structured()` Map.
- New unit test: agent node with `jsonOutput=false` → `.structured()` is `null`, `.text()` contains the response.

**Effort:** Medium. **Risk:** Medium (existing tests must be adjusted because `nodeResults` is now `Object` instead of `String`).

**Dependency:** Step 2 (WorkflowContext), Step 3 (use `EngineResult.structured()`).

---

## Step 5 — Template resolver (data piping) in `WorkflowExecutor`

**What:** Before executing an agent node: resolve `nodeInput` and parameters via `resolveTemplate()`.

```java
private Object resolveTemplate(String template, WorkflowContext ctx) {
    // Pattern: ${node_id.output} or ${node_id.output.field} or ${node_id.output.nested.path}
    Pattern p = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)\\.output(\\.([a-zA-Z0-9_.]+))?\\}");
    Matcher m = p.matcher(template);
    if (m.find()) {
        String targetNodeId = m.group(1);
        String propertyPath = m.group(3);
        Object previousOutput = ctx.getNodeOutput(targetNodeId);
        if (previousOutput == null) return null;
        if (propertyPath == null || propertyPath.isEmpty()) return previousOutput;
        return resolveNestedProperty(previousOutput, propertyPath);
    }
    return template;
}

private Object resolveNestedProperty(Object target, String path) {
    String[] parts = path.split("\\.");
    Object current = target;
    for (String part : parts) {
        if (current instanceof Map) {
            current = ((Map<?, ?>) current).get(part);
            if (current == null) return null;
        } else {
            return null;
        }
    }
    return current;
}
```

**Why now:** Builds on step 4 (node outputs in context). Can be implemented as a standalone utility class and tested before the WorkflowExecutor uses it.

**Testable:**
- Unit test `TemplateResolverTest`: `${node_1.output}` → entire output, `${node_1.output.status}` → field, `${node_1.output.user.email}` → nested, missing node → `null`, no placeholder → original string.
- Integration: workflow with 2 agent nodes, where node 2 uses `${node_1.output.data}` as input.

**Effort:** Low–Medium. **Risk:** Low (pure string processing, isolable).

**Dependency:** Step 4 (WorkflowContext with nodeOutputs).

---

## Step 6 — Switch `ConditionEvaluator` to `WorkflowContext`

**What:** `ConditionEvaluator.matches(String condition, String context)` → `ConditionEvaluator.matches(String condition, WorkflowContext ctx)`.

New condition syntax:
- `contains:arg` → as before (on string context)
- `status == 'SUCCESS'` → checks `ctx.getState("lastStatus")` or `ctx.getNodeOutput(nodeId)`
- `node_1.output.classification == 'VIP_ORDER'` → checks structured output

**Why now:** Builds on steps 2 + 4 (WorkflowContext + node outputs). Can be developed in parallel with step 5 (no dependency).

**Testable:**
- Unit test: `status == 'SUCCESS'` with `ctx.setState("lastStatus", "SUCCESS")` → true.
- Unit test: `node_1.output.classification == 'VIP_ORDER'` with `ctx.setNodeOutput("node_1", Map.of("classification", "VIP_ORDER"))` → true.
- Backward compatibility: `contains:foo` with string context → true (fallback).

**Effort:** Medium. **Risk:** Medium (existing ConditionEvaluator tests must be adjusted).

**Dependency:** Step 2 (WorkflowContext).

---

## Step 7 — LLM node types (`llm_eval`, `llm_summarize`)

**What:** New node types in the `WorkflowExecutor` switch:

```java
case "llm_eval" -> {
    String prompt = resolveTemplate(node.data().title(), ctx); // or node.data().exitCondition()
    // ... prompt from node.data
    EngineResult result = engine.runPrompt(prefixedName, prompt, config);
    // Parse structured result
    Map<String, Object> structured = toMap(result.structured());
    ctx.setNodeOutput(nodeId, structured != null ? structured : result.text());
    ctx.setState("lastStatus", structured != null ? structured.get("status") : "SUCCESS");
    nodeResults.put(nodeId, new NodeResult(result.text(), structured));
}
case "llm_summarize" -> {
    // Collect data from predecessors via ${...}
    String inputData = resolveTemplate(node.data().defaultTarget(), ctx); // or specific field
    String prompt = "Summarize: " + inputData;
    EngineResult result = engine.runPrompt(prefixedName, prompt, config);
    ctx.setNodeOutput(nodeId, result.text());
    nodeResults.put(nodeId, new NodeResult(result.text(), null));
}
```

**Why now:** Builds on steps 3–6 (`EngineResult.structured`, `WorkflowContext`, template resolver, `ConditionEvaluator`). Can only be implemented once the previous steps are in place.

**Testable:**
- Unit test with mock engine: `llm_eval` node → `ctx.getNodeOutput(nodeId)` contains a Map.
- Integration: workflow with an `llm_eval` node followed by a decision node with an edge condition on `node_eval.output.classification`.

**Effort:** Low–Medium. **Risk:** Low (uses existing infrastructure).

**Dependency:** Steps 3, 4, 5, 6.

---

## Step 8 — Implement Fork/Join

**What:**
- **Fork**: parallel execution of multiple branches. In synchronous mode: sequentially with separate contexts, collecting results.
- **Join**: wait for all incoming branches, merge results (e.g. into a Map or List).

```java
case "fork" -> {
    List<String> targets = TopologicalSort.outgoingTargets(def, nodeId);
    Map<String, Object> forkResults = new HashMap<>();
    for (String target : targets) {
        forkResults.put(target, "forked:" + target);
    }
    ctx.setState("forkResults", forkResults);
    nodeResults.put(nodeId, new NodeResult("forked", forkResults));
}
case "join" -> {
    List<String> sources = TopologicalSort.incomingSources(def, nodeId);
    Map<String, Object> merged = new HashMap<>();
    for (String src : sources) {
        merged.put(src, ctx.getNodeOutput(src));
    }
    ctx.setNodeOutput(nodeId, merged);
    nodeResults.put(nodeId, new NodeResult("joined", merged));
}
```

**Why now:** Builds on step 4 (`WorkflowContext`). Can be developed in parallel with step 7.

**Testable:**
- Unit test: fork with 2 branches → both executed, results in `forkResults`.
- Unit test: join with 2 incoming nodes → merged Map contains both.

**Effort:** Medium–High. **Risk:** Medium (parallelization in synchronous mode is simplified; real parallelism in Temporal).

**Dependency:** Step 4 (WorkflowContext).

---

## Step 9 — Implement Loop (body steps)

**What:** A loop node executes `bodySteps` (list of node IDs) repeatedly until `exitCondition` is satisfied or `maxIterations` is reached.

```java
case "loop" -> {
    int maxIter = node.data().maxIterations();
    String exitCond = node.data().exitCondition();
    List<String> body = node.data().bodySteps();
    for (int i = 0; i < maxIter; i++) {
        for (String bodyNodeId : body) {
            // Execute the body node (recursively or inline)
        }
        if (exitCond != null && !exitCond.isBlank() && ConditionEvaluator.matches(exitCond, ctx)) {
            break;
        }
    }
    nodeResults.put(nodeId, new NodeResult("loop completed", null));
}
```

**Why now:** Builds on step 6 (`ConditionEvaluator` on `WorkflowContext`) and step 8 (dispatch refactoring for recursive execution).

**Testable:**
- Unit test: loop with 3 iterations, exit condition after 2nd iteration → breaks after 2.
- Unit test: loop without exit condition → runs through `maxIterations`.

**Effort:** Medium. **Risk:** Medium (recursive execution can get complex).

**Dependency:** Steps 4, 6, 8.

---

## Step 10 — Subworkflow expansion

**What:** `SubWorkflowExpander` expands subworkflow nodes into the main graph before execution. Agent configs are merged.

```java
public class SubWorkflowExpander {
    public static WorkflowDefinition expand(WorkflowDefinition def, WorkflowStore store) {
        // For each subworkflow node: load referenced workflow,
        // merge nodes/edges/agents into the main graph
    }
}
```

**Why now:** Builds on step 1 (edge condition) and step 4 (`WorkflowContext`). Should be implemented after the core features.

**Testable:**
- Unit test: workflow with a subworkflow node → expands to a flat graph with all nodes.
- Unit test: agent configs of the subworkflow are merged into `agents[]`.

**Effort:** Medium–High. **Risk:** Medium.

**Dependency:** Steps 1, 4.

---

## Step 12 — Frontend: edge conditions + data-piping UI

**What:**
- `PropertyPanel`: edge condition editor (e.g. dropdown for operators + value).
- `GraphEditor`: data-piping hints in node properties (e.g. `${node_1.output.status}`).
- `toBackend()`/`fromBackend()`: serialize the `condition` field on edges.

**Why now:** Builds on step 1 (`Edge.condition` in the data model). Can be developed in parallel with backend steps.

**Testable:**
- Playwright E2E: create an edge with a condition, save, reload → condition retained.
- Playwright E2E: enter a data-piping placeholder, save, reload → retained.

**Effort:** Medium. **Risk:** Low.

**Dependency:** Step 1.

---

## Dependency graph

```
Step 0 (tests green)
  │
  ├── Step 1 (Edge.condition) ──────────────────────┐
  │                                                    │
  ├── Step 2 (WorkflowContext) ──┐                  │
  │                                 │                  │
  ├── Step 3 (use EngineResult.structured) ──┐    │
  │                                               │    │
  │   ┌───────────────────────────────────────────┘    │
  │   │                                                │
  │   ├── Step 4 (Executor → WorkflowContext) ──┐   │
  │   │                                            │   │
  │   ├── Step 5 (Template resolver) ──────────┤   │
  │   │                                            │   │
  │   └── Step 6 (ConditionEvaluator → State) ─┤   │
  │                                                │   │
  │   ┌────────────────────────────────────────────┘   │
  │   │                                                │
  │   ├── Step 7 (LLM node types) ──────────────┤   │
  │   │                                            │   │
  │   ├── Step 8 (Fork/Join) ──────────────────┤   │
  │   │                                            │   │
  │   └── Step 9 (Loop body steps) ────────────┤   │
  │                                                │   │
  │   ┌────────────────────────────────────────────┘   │
  │   │                                                │
  │   └── Step 10 (Subworkflow expansion) ──────┘   │
  │                                                    │
  └── Step 12 (Frontend: edge cond + data piping) ──┘
```

---

## Recommended sprint planning

| Sprint | Steps | Goal |
|---|---|---|
| **Sprint 1** | 0, 1, 2, 3 | Data model + context + structured output (isolatedly testable) |
| **Sprint 2** | 4, 5, 6 | Switch executor to state + data piping + conditions (core integration) |
| **Sprint 3** | 7, 8, 9 | LLM nodes + fork/join + loop (complete workflow semantics) |
| **Sprint 4** | 10, 12 | Subworkflow + frontend (end-to-end) |

---

## Minimal "quick win" (1–2 days)

If only a small step is possible, I recommend **steps 1 + 5**:
- Add `Edge.condition` (data model)
- Implement the template resolver as a utility class + unit tests

This immediately brings data-piping capability (`${node_1.output}`) into the WorkflowExecutor without breaking the existing string-based execution (the resolver returns the original string when no placeholder is found).

---

## Test strategy per step

| Step | Test type | Test file (new/extended) |
|---|---|---|
| 0 | Regression | Existing tests |
| 1 | Unit | `WorkflowDefinitionTest` |
| 2 | Unit | `WorkflowContextTest` |
| 3 | Unit + Mock | `QocEngineStructuredOutputTest` |
| 4 | Unit + Integration | `WorkflowExecutorTest` |
| 5 | Unit | `TemplateResolverTest` |
| 6 | Unit | `ConditionEvaluatorTest` |
| 7 | Unit + Integration | `WorkflowExecutorLlmNodeTest` |
| 8 | Unit | `ForkJoinTest` |
| 9 | Unit | `LoopNodeTest` |
| 10 | Unit | `SubWorkflowExpanderTest` |
| 12 | E2E (Playwright) | `e2e-workflow-hybrid.spec.ts` |

---

---

## TODO — Temporal-related tasks (later, as an optional layer)

The following tasks were removed from the implementation path and will be added in a later step (after stable synchronous execution). They can be implemented via DB tracing and custom components.

- **DB tracing / event history:** Write workflow events (node started, node finished, error) to the database to make executions traceable and enable crash recovery.
- **Crash recovery / checkpoint resume:** Load the last checkpoint from the DB and continue the workflow from there (without Temporal replay).
- **HITL signals:** Asynchronous waiting for human input — the node pauses, run status = `paused`, the UI shows an approval dialog, on approval → resume.
- **Saga / compensation:** Rollback chain on errors — define compensation logic in nodes (e.g. `compensate` callback).
- **Durable execution wrapper:** Wrap Temporal (or a custom queue) as an optional execution layer around the engine — the engine stays Temporal-free; Temporal only handles durability.
- **Multi-node scaling:** Distributed execution across multiple workers (only when needed).
- **Temporal UI integration:** Run history and event inspection in the UI (optional, can be replaced by custom run views).

---

## References

- Architecture document: `qoc/docs/engineering/ReAct-Workflow.md`
- Original conversation: `qoc/docs/ReAct.txt`
- QocEngine: `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/QocEngine.java`
- WorkflowExecutor: `qoc/workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/WorkflowExecutor.java`
- WorkflowDefinition: `qoc/workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/model/WorkflowDefinition.java`
- TopologicalSort: `qoc/workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/TopologicalSort.java`
- ConditionEvaluator: `qoc/workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/ConditionEvaluator.java`


---

## Runner → getNextStep transformation (analysis)

### Which runner functions are still needed?

| Component | Still needed? | Rationale |
|---|---|---|
| **TopologicalSort** | ⚠️ Partially | `sort()` is replaced by getNextStep. But `incomingSources()` and `outgoingTargets()` are used by getNextStep. Cycle detection remains for validation. |
| **ConditionEvaluator** | ✅ Yes, but extended | Currently string-based (`contains:`, `equals:`). Must be switched to `WorkflowContext` (state-based conditions like `status == 'SUCCESS'`). |
| **SubWorkflowExpander** | ✅ Yes | Flattens sub-workflows before execution. Independent of the execution strategy. |
| **GraphCompiler** | ✅ Yes | Validation (node types, edges, agent refs, cycles). Independent. |
| **WorkflowStore** | ✅ Yes | JSON persistence. Independent. |
| **WorkflowExecutor** | ❌ Replaced | Replaced by `NextStepProvider` + `WorkflowLoop`. |
| **RunAgentActivity** | ❌ Removed | Temporal removed. |
| **ReactFlowWorkflowImpl** | ❌ Removed | Temporal removed. |

### Why TopologicalSort is not enough

The current `WorkflowExecutor` uses `TopologicalSort.sort()`:

```java
List<String> order = TopologicalSort.sort(def);  // Linear, DAG-only
for (String nodeId : order) { ... }  // Sequential, no branching
```

**Problems:**
1. **No branching:** TopologicalSort returns a linear order. For decision nodes (multiple outgoing edges), all successors are included in the order — even those that are not chosen.
2. **No loops:** TopologicalSort treats cycles as errors. But loop nodes (bodySteps + exitCondition) are explicitly cycles.
3. **No dynamic path choice:** The context (e.g. the result of an agent node) influences which path is taken. TopologicalSort does not account for this.

### NextStepProvider — the new core class

`NextStepProvider` replaces `WorkflowExecutor` and `TopologicalSort.sort()`. It reads the workflow graph and the `WorkflowContext` and determines the next node.

#### Interface

```java
public interface NextStepProvider {
    String getNextNodeId(WorkflowDefinition def, WorkflowContext ctx);
    List<String> getOutgoingTargets(WorkflowDefinition def, String nodeId);
    List<String> getIncomingSources(WorkflowDefinition def, String nodeId);
}
```

#### Implementation (GraphNextStepProvider)

```java
public class GraphNextStepProvider implements NextStepProvider {

    @Override
    public String getNextNodeId(WorkflowDefinition def, WorkflowContext ctx) {
        String currentId = ctx.getCurrentNodeId();

        // Case 1: workflow start
        if (currentId == null) {
            String startNodeId = findStartNode(def);
            ctx.setCurrentNodeId(startNodeId);
            return startNodeId;
        }

        // Case 2: find outgoing edges
        List<WorkflowDefinition.Edge> outgoing = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.source().equals(currentId)) outgoing.add(e);
        }

        if (outgoing.isEmpty()) return "DONE";

        // Case 3: linear path
        if (outgoing.size() == 1) {
            String target = outgoing.get(0).target();
            ctx.setCurrentNodeId(target);
            return target;
        }

        // Case 4: branching — check edge conditions
        for (WorkflowDefinition.Edge e : outgoing) {
            if (e.condition() != null && !e.condition().isBlank()
                    && ConditionEvaluator.matches(e.condition(), ctx)) {
                ctx.setCurrentNodeId(e.target());
                return e.target();
            }
        }

        // Fallback: defaultTarget of the node or first edge
        WorkflowDefinition.Node currentNode = findNode(def, currentId);
        if (currentNode != null && currentNode.data() != null
                && currentNode.data().defaultTarget() != null
                && !currentNode.data().defaultTarget().isBlank()) {
            String target = currentNode.data().defaultTarget();
            ctx.setCurrentNodeId(target);
            return target;
        }

        String target = outgoing.get(0).target();
        ctx.setCurrentNodeId(target);
        return target;
    }

    @Override
    public List<String> getOutgoingTargets(WorkflowDefinition def, String nodeId) {
        List<String> targets = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.source().equals(nodeId)) targets.add(e.target());
        }
        return targets;
    }

    @Override
    public List<String> getIncomingSources(WorkflowDefinition def, String nodeId) {
        List<String> sources = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.target().equals(nodeId)) sources.add(e.source());
        }
        return sources;
    }

    private String findStartNode(WorkflowDefinition def) {
        Set<String> targets = new HashSet<>();
        for (WorkflowDefinition.Edge e : def.edges()) targets.add(e.target());
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (!targets.contains(n.id())) return n.id();
        }
        return def.nodes().isEmpty() ? null : def.nodes().get(0).id();
    }

    private WorkflowDefinition.Node findNode(WorkflowDefinition def, String id) {
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }
}
```

#### What NextStepProvider takes from the runner

| Runner function | NextStepProvider equivalent |
|---|---|
| `TopologicalSort.sort()` | `getNextNodeId()` — navigates dynamically instead of linearly |
| `TopologicalSort.incomingSources()` | `getIncomingSources()` — for join nodes |
| `TopologicalSort.outgoingTargets()` | `getOutgoingTargets()` — for fork nodes |
| `ConditionEvaluator.matches()` | Extended for `WorkflowContext` |
| `WorkflowExecutor` dispatch logic | Remains in `WorkflowLoop` (node execution) |

### Approach: extend the QocEngine loop (no separate WorkflowLoop)

**Decision:** Instead of a separate `WorkflowLoop`, the existing `QocEngine.runPrompt()` loop is extended. The loop becomes **session-type-dependent**:
- `sessionType == "agent"` → LLM calls (existing, unchanged)
- `sessionType == "workflow"` → deterministic `getNextStep()` calls

**Why:** QocEngine already has a complete agent loop (while loop, retry, loop detection, compaction, turn policy, resilience). Reimplementing this would create duplication and inconsistency.

#### Extension of QocEngine.runPrompt()

New optional parameters: `WorkflowDefinition workflowDef` and `WorkflowContext workflowCtx`. When both are non-null, the loop runs in **workflow mode**.

In workflow mode:
1. `getNextStep(workflowDef, workflowCtx)` is called instead of `step.run()`
2. getNextStep returns an `AssistantMessage` with `ToolCall(s)` (subagentTool)
3. The tool calls are executed as before (ToolRuntime or NodeDispatcher)
4. Observations are written to the WorkflowContext
5. When `isFinished == true`, the loop aborts

#### getNextStep as a method

```java
public AssistantMessage getNextStep(WorkflowDefinition def, WorkflowContext ctx) {
    String currentId = ctx.getCurrentNodeId();

    if (currentId == null) {
        String startNodeId = findStartNode(def);
        ctx.setCurrentNodeId(startNodeId);
        return createSubagentToolCall(startNodeId, def, ctx);
    }

    List<Edge> outgoing = findOutgoingEdges(def, currentId);
    if (outgoing.isEmpty()) {
        return new AssistantMessage("Workflow finished.", List.of(), true);
    }

    if (outgoing.size() == 1) {
        String target = outgoing.get(0).target();
        ctx.setCurrentNodeId(target);
        return createSubagentToolCall(target, def, ctx);
    }

    for (Edge e : outgoing) {
        if (e.condition() != null && !e.condition().isBlank()
                && ConditionEvaluator.matches(e.condition(), ctx)) {
            ctx.setCurrentNodeId(e.target());
            return createSubagentToolCall(e.target(), def, ctx);
        }
    }

    String target = outgoing.get(0).target();
    ctx.setCurrentNodeId(target);
    return createSubagentToolCall(target, def, ctx);
}
```

#### createSubagentToolCall

```java
private AssistantMessage createSubagentToolCall(String nodeId, WorkflowDefinition def, WorkflowContext ctx) {
    WorkflowDefinition.Node node = findNode(def, nodeId);
    Map<String, Object> args = new HashMap<>();
    if (node.data() != null) {
        for (Map.Entry<String, Object> e : node.data().entrySet()) {
            if (e.getValue() instanceof String) {
                args.put(e.getKey(), TemplateResolver.resolve((String) e.getValue(), ctx));
            } else {
                args.put(e.getKey(), e.getValue());
            }
        }
    }
    args.put("nodeType", node.type());
    args.put("nodeId", nodeId);

    return new AssistantMessage(
        "Execute workflow node [" + nodeId + "] (type: " + node.type() + ")",
        List.of(new ToolCall("call_" + nodeId, "subagentTool", args)),
        false
    );
}
```

#### NodeDispatcher as subagentTool handler

The `NodeDispatcher` is registered as the handler for the `subagentTool`. When the loop executes a `subagentTool` call, it delegates to the NodeDispatcher, which runs the correct logic based on `nodeType`.

#### Advantages

1. **No duplication:** Existing resilience (retry, loop detection, compaction, turn policy, preflight, webhook) applies to both modes.
2. **Unified loop:** One loop for agent mode and workflow mode.
3. **ReAct-compatible:** getNextStep returns an AssistantMessage (with ToolCall) — exactly as in the ReAct pattern.
4. **SubagentTool as universal executor:** Every node is executed as a subagentTool call.

#### QocEngine changes

| Change | Description |
|---|---|
| `runPrompt()` signature | New optional parameters: `WorkflowDefinition workflowDef`, `WorkflowContext workflowCtx` |
| Loop branch | `if (workflowDef != null)` → getNextStep path, otherwise LLM path |
| getNextStep() | New method in QocEngine (or as callback/strategy) |
| WorkflowContext | Must be passed to QocEngine |
| NodeDispatcher | Used as tool handler for subagentTool |

```java
public class WorkflowLoop {

    private final NextStepProvider nextStep;
    private final NodeDispatcher dispatcher;
    private final WorkflowContext ctx;
    private final WorkflowDefinition def;

    public WorkflowLoop(WorkflowDefinition def, WorkflowContext ctx,
                        NextStepProvider nextStep, NodeDispatcher dispatcher) {
        this.def = def;
        this.ctx = ctx;
        this.nextStep = nextStep;
        this.dispatcher = dispatcher;
    }

    public NodeResult run(String input) {
        ctx.setCurrentNodeId(null);
        String lastResult = input == null ? "" : input;

        int maxSteps = 100;
        int step = 0;

        while (step < maxSteps) {
            step++;
            String nodeId = nextStep.getNextNodeId(def, ctx);

            if ("DONE".equals(nodeId)) break;

            WorkflowDefinition.Node node = findNode(def, nodeId);
            if (node == null) throw new IllegalStateException("Node not found: " + nodeId);

            NodeResult result = dispatcher.dispatch(node, def, ctx, lastResult);
            ctx.setNodeOutput(nodeId, result.structured() != null ? result.structured() : result.text());
            lastResult = result.text();

            ctx.setState("lastResult", lastResult);
            if (result.structured() != null && result.structured().containsKey("status")) {
                ctx.setState("lastStatus", result.structured().get("status"));
            }

            // Loop node: process bodySteps
            if ("loop".equals(node.type())) {
                int maxIter = node.data().maxIterations();
                String exitCond = node.data().exitCondition();
                List<String> body = node.data().bodySteps();
                for (int i = 0; i < maxIter; i++) {
                    for (String bodyNodeId : body) {
                        WorkflowDefinition.Node bodyNode = findNode(def, bodyNodeId);
                        if (bodyNode != null) {
                            NodeResult bodyResult = dispatcher.dispatch(bodyNode, def, ctx, lastResult);
                            ctx.setNodeOutput(bodyNodeId, bodyResult.structured() != null ? bodyResult.structured() : bodyResult.text());
                            lastResult = bodyResult.text();
                        }
                    }
                    if (exitCond != null && !exitCond.isBlank()
                            && ConditionEvaluator.matches(exitCond, ctx)) {
                        break;
                    }
                }
            }

            // Fork node: parallel branches
            if ("fork".equals(node.type())) {
                List<String> targets = nextStep.getOutgoingTargets(def, nodeId);
                Map<String, Object> forkResults = new HashMap<>();
                for (String target : targets) {
                    WorkflowDefinition.Node forkNode = findNode(def, target);
                    if (forkNode != null) {
                        NodeResult forkResult = dispatcher.dispatch(forkNode, def, ctx, lastResult);
                        forkResults.put(target, forkResult.structured() != null ? forkResult.structured() : forkResult.text());
                    }
                }
                ctx.setState("forkResults", forkResults);
            }
        }

        if (step >= maxSteps) {
            throw new IllegalStateException("Workflow exceeded max steps (" + maxSteps + ")");
        }

        String finalResult = lastResult;
        if (finalResult.isBlank()) {
            finalResult = ctx.getNodeOutputs().isEmpty() ? "(no result)"
                    : ctx.getNodeOutputs().values().stream()
                            .reduce((a, b) -> b).map(Object::toString).orElse("(no result)");
        }
        return new NodeResult(finalResult, null);
    }

    private WorkflowDefinition.Node findNode(WorkflowDefinition def, String id) {
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }
}
```

### NodeDispatcher — node execution (extracted from WorkflowExecutor)

```java
public interface NodeDispatcher {
    NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                       WorkflowContext ctx, String input);
}

public class DefaultNodeDispatcher implements NodeDispatcher {

    private final QocEngine engine;
    private final WorkflowStore store;
    private final SubWorkflowExpander expander;

    public DefaultNodeDispatcher(QocEngine engine, WorkflowStore store) {
        this.engine = engine;
        this.store = store;
        this.expander = new SubWorkflowExpander(store);
    }

    @Override
    public NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                              WorkflowContext ctx, String input) {
        String type = node.type();
        switch (type) {
            case "agent" -> {
                String agentRef = node.data().agentRef();
                WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);
                String prefixedName = prefixedName(agentDef, agentRef);
                QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName);

                String nodeInput = input;
                List<String> incoming = TopologicalSort.incomingSources(def, node.id());
                if (!incoming.isEmpty() && incoming.size() == 1) {
                    Object predOutput = ctx.getNodeOutput(incoming.get(0));
                    if (predOutput != null && !predOutput.toString().isBlank()) {
                        nodeInput = predOutput.toString();
                    }
                }

                QocEngine.EngineResult result = engine.runPrompt(prefixedName, nodeInput, config);
                Map<String, Object> structured = toMap(result.structured());
                return new NodeResult(result.text(), structured);
            }
            case "decision" -> {
                String ctxStr = ctx.getState("lastResult") != null
                        ? ctx.getState("lastResult").toString() : input;
                List<WorkflowDefinition.Branch> branches = node.data().branches();
                String target = node.data().defaultTarget();
                for (WorkflowDefinition.Branch b : branches) {
                    if (ConditionEvaluator.matches(b.condition(), ctx)) {
                        target = b.target();
                        break;
                    }
                }
                ctx.setNodeOutput(node.id(), "decision -> " + target);
                return new NodeResult("decision -> " + target, Map.of("target", target));
            }
            case "start" -> {
                ctx.setNodeOutput(node.id(), "started");
                return new NodeResult("started", null);
            }
            case "hitl" -> {
                ctx.setNodeOutput(node.id(), "approved");
                return new NodeResult("approved", null);
            }
            case "subworkflow" -> {
                String ref = node.data().workflowRef();
                WorkflowDefinition subWf = store.get(ref);
                if (subWf == null) {
                    return new NodeResult("subworkflow not found: " + ref, null);
                }
                WorkflowDefinition expanded = expander.expand(subWf);
                WorkflowContext subCtx = new WorkflowContext();
                WorkflowLoop subLoop = new WorkflowLoop(expanded, subCtx, new GraphNextStepProvider(), this);
                NodeResult subResult = subLoop.run(input);
                ctx.setNodeOutput(node.id(), subResult.text());
                return new NodeResult("subworkflow:" + ref + " → " + subResult.text(), subResult.structured());
            }
            case "end" -> {
                ctx.setNodeOutput(node.id(), "done");
                return new NodeResult("done", null);
            }
            default -> {
                ctx.setNodeOutput(node.id(), "skipped (" + type + ")");
                return new NodeResult("skipped (" + type + ")", null);
            }
        }
    }

    // ... prefixedName, toAgentConfig, findAgent ...
}
```

### ConditionEvaluator — extended for WorkflowContext

```java
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    public static boolean matches(String condition, WorkflowContext ctx) {
        if (condition == null || condition.isBlank()) return true;

        String c = condition.trim();

        // State-based conditions: "status == 'SUCCESS'"
        if (c.contains("==")) {
            String[] parts = c.split("==");
            String left = parts[0].trim();
            String right = parts[1].trim().replace("'", "").replace("\"", "");

            if (left.equals("status") || left.equals("lastStatus")) {
                Object val = ctx.getState("lastStatus");
                return right.equals(String.valueOf(val));
            }

            // Node-output-based: "node_1.output.classification == 'VIP_ORDER'"
            if (left.startsWith("node_") && left.contains(".output")) {
                String[] pathParts = left.split("\\.");
                String nodeId = pathParts[0];
                String field = pathParts.length > 2 ? pathParts[2] : null;

                Object output = ctx.getNodeOutput(nodeId);
                if (output instanceof Map) {
                    Object val = field != null ? ((Map<?, ?>) output).get(field) : output;
                    return right.equals(String.valueOf(val));
                }
                return right.equals(String.valueOf(output));
            }
        }

        // Legacy string-based conditions (fallback)
        String ctxStr = ctx.getState("lastResult") != null
                ? ctx.getState("lastResult").toString() : "";
        int colon = c.indexOf(':');
        String op = (colon < 0 ? c : c.substring(0, colon)).trim().toLowerCase();
        String arg = colon < 0 ? "" : c.substring(colon + 1);
        return switch (op) {
            case "contains" -> ctxStr.toLowerCase().contains(arg.toLowerCase());
            case "notcontains" -> !ctxStr.toLowerCase().contains(arg.toLowerCase());
            case "starts" -> ctxStr.trim().toLowerCase().startsWith(arg.trim().toLowerCase());
            case "equals" -> ctxStr.trim().equalsIgnoreCase(arg.trim());
            case "truthy" -> ctxStr.toLowerCase().contains("true");
            case "falsy" -> ctxStr.isBlank() || ctxStr.toLowerCase().contains("false");
            default -> false;
        };
    }
}
```

### WorkflowLoop vs. WorkflowExecutor — comparison

| Aspect | WorkflowExecutor (old) | WorkflowLoop (new) |
|---|---|---|
| Navigation | TopologicalSort (linear, DAG) | NextStepProvider (dynamic, graph-based) |
| Branching | Decision nodes only (branches in NodeData) | All nodes with multiple outgoing edges |
| Loops | Loop node with bodySteps (stub) | Loop node with real iteration + exit condition |
| Fork/Join | Stubs | Real parallel execution + merge |
| Sub-workflows | Stub | Recursive expansion + execution |
| Data piping | No (string context only) | Template resolver (`${node_X.output.field}`) |
| State | `Map<String, String>` | `WorkflowContext` (structured outputs) |
| Conditions | String-based | State-based (structured outputs) |

### Migration path

```
Phase 1: NextStepProvider + WorkflowContext + ConditionEvaluator (state-based)
  → Unit tests: navigation, conditions, state
Phase 2: WorkflowLoop + NodeDispatcher (ported from WorkflowExecutor)
  → Integration tests: simple workflows (start → agent → end)
Phase 3: Template resolver + data piping
  → Integration tests: workflows with data piping
Phase 4: LLM node types (llm_eval, llm_summarize)
  → Integration tests: hybrid workflows
Phase 5: Fork/Join + Loop + Sub-workflow (real implementation)
  → Integration tests: complex workflows
Phase 6: HTTP server + checkpoint (crash recovery)
  → E2E tests
```

### Summary

**Runner functions that remain:**
- `TopologicalSort` → `incomingSources()`, `outgoingTargets()` (as helper methods in NextStepProvider)
- `ConditionEvaluator` → extended for `WorkflowContext`
- `SubWorkflowExpander` → unchanged
- `GraphCompiler` → unchanged (possibly add `llm_eval`/`llm_summarize` to VALID_NODE_TYPES)
- `WorkflowStore` → unchanged

**What is replaced:**
- `WorkflowExecutor` → `NextStepProvider` + `WorkflowLoop` + `NodeDispatcher`
- `TopologicalSort.sort()` → `NextStepProvider.getNextNodeId()`

**Core principle:** Instead of linear topological-sort navigation, the graph is navigated dynamically via `getNextNodeId()`. The `WorkflowContext` holds the state. `ConditionEvaluator` checks against structured outputs. `NodeDispatcher` executes each node. The loop iterates until `getNextNodeId()` returns "DONE".