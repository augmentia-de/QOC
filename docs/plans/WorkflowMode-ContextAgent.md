# Dynamic State-Driven Workflow & Context Hygiene — Plan

> **Status:** Plan, 2026-11-13  
> **Reference:** `docs/plans/Hybrid.md`, `docs/engineering/Additional-Nodes.md`,
> `docs/engineering/Workflow.md`, `docs/engineering/Two-Agent-Systems.md`  
> **Goal:** Rebuild the workflow from a static, fully context-carrying graph into
> a dynamic, state-driven system with context hygiene. After every agent run, a
> deterministic evaluation takes place; data is injected into the context via
> references/links rather than full text.

---

## 0. Correction of Previous Assumptions

### 0.1 Clarification: WorkflowMode and JSON Integration

The workflow runs in `workflowMode`. Features are deliberately integrated via
JSON structures (`mcpArgs`, structured agent results, `stateUpdates`,
`injectRefs`, `postGuard`). The `context_agent_executor` is a special
workflow node that invokes an LLM in a child session via `runAgent`. It is not
a `SubagentTool` call and does not receive a shared workflow context through
the subagent path.

The previous planning rested on three assumptions that are problematic for
large, dynamic workflows:

1. **Static context:** `buildFullContext` sends all previous node outputs in
   fixed `def.nodes()` order. With large data or long runs, the context window
   explodes.
2. **Always full context:** The `context_agent_executor` receives the entire
   previous history. That is sensible for synthesis, but not for every step;
   many agents need only a minimal, focused context.
3. **Static graph flow:** Navigation essentially follows the edges. Dynamic
   back-jumps with newly injected data are possible only via `loop` and
   `decision`, but not as a first-class mechanism.

The correction:

- **Deterministic evaluation after every agent run** controls the flow.
- **External data/artifacts** are held in storage and injected into prompts
  only as references/links.
- **The workflow is a dynamic state machine** that recalibrates itself after
  every agent step.

---

## 1. Core Concept

### 1.1 Deterministic Check After the Agent Run

The agent returns a structured result (JSON Schema). A deterministic guard
evaluates hard conditions:

- `score < 0.8`
- `missing_fields == true`
- `status == "retry"`

The guard controls the graph flow:

- proceed to the next step,
- jump back (loop) with additional parameters,
- invoke another agent,
- inject additional data/links.

The LLM does not decide about the workflow; it only delivers the result.

### 1.2 Context-Window Hygiene

- The LLM/subagent **never** receives the entire historical ballast or huge
  raw data.
- External data, artifacts, and links are held in an `ArtifactStore`.
- The deterministic control layer selectively injects new data pointers or
  links into the prompt on transitions or back-jumps.
- The agent always works with a minimal, focused context.

### 1.3 Merging the Agent Loop and the Workflow

- The agent loop is only the execution tool for a subtask.
- The workflow is not a rigid, precomputed path list (`def.nodes()` in fixed
  order), but a dynamic state machine.
- After every agent step the state is recalibrated.

---

## 2. Target Architecture

```text
WorkflowExecutor
 └─ QocEngine.runPrompt(workflowSession, WorkflowStepProvider)
     └─ WorkflowStepProviderImpl
         ├─ getNextNodeId(def, ctx)   // dynamic state machine
         ├─ DefaultNodeDispatcher.dispatch(node, def, ctx, input)
         │    ├─ Agent-Node
         │    │    ├─ ContextAssembler.assemble(ctx, policy, task)
         │    │    ├─ runAgent(..., compactionEnabled=false)
         │    │    └─ optional Post-Guard
         │    ├─ Guard-Node
         │    │    ├─ evaluate conditions
         │    │    ├─ set state
         │    │    └─ Inject-Refs / set nextNode
         │    ├─ Inject-Node
         │    │    └─ Artifact-Refs into ctx.contextInclude
         │    ├─ Decision / MCP / Compact / HITL
         │    └─ ...
         └─ JournalWorkflowEventSink
```

---

## 3. Data Model

### 3.1 `ArtifactRef` and `ArtifactStore`

**New file:** `workflow-runner/.../runner/ArtifactRef.java`

```java
public record ArtifactRef(
        String id,
        String type,        // json, text, link, file, mcp-result
        String uri,         // storage key or URL
        String summary,     // short description for prompt
        long sizeBytes,
        java.time.Instant createdAt
) {}
```

**New file:** `workflow-runner/.../runner/ArtifactStore.java`

```java
public interface ArtifactStore {
    String put(ArtifactRef ref, Object payload);
    java.util.Optional<Object> get(String id);
    java.util.List<ArtifactRef> list();
    boolean remove(String id);
}
```

**New file:** `workflow-runner/.../runner/InMemoryArtifactStore.java`

- First implementation for JVM mode.
- JDBC implementation later, analogous to `AgentStore`.

### 3.2 `WorkflowContext` Extensions

**File:** `workflow-runner/.../runner/WorkflowContext.java`

New fields:

```java
private final Map<String, ArtifactRef> artifacts = new HashMap<>();
private final List<String> contextInclude = new ArrayList<>();
private final List<String> contextExclude = new ArrayList<>();
private Map<String, Object> lastStructuredResult = Map.of();
```

New methods:

```java
public void addArtifact(ArtifactRef ref) { artifacts.put(ref.id(), ref); }
public Map<String, ArtifactRef> getArtifacts() { return Map.copyOf(artifacts); }
public void includeContext(String ref) { if (!contextInclude.contains(ref)) contextInclude.add(ref); }
public void excludeContext(String ref) { contextExclude.add(ref); }
public List<String> getContextInclude() { return List.copyOf(contextInclude); }
public List<String> getContextExclude() { return List.copyOf(contextExclude); }
public void setLastStructuredResult(Map<String, Object> result) { ... }
public Map<String, Object> getLastStructuredResult() { ... }
```

Persistence (pragmatic, without journal schema changes):

- `artifacts`, `contextInclude`, `contextExclude`, and `lastStructuredResult`
  are stored under the keys `_artifacts`, `_contextInclude`,
  `_contextExclude`, and `_lastStructuredResult` in `workflowState`.
- On resume they are restored from `workflowState`.

### 3.3 `ContextAssembler`

**New file:** `workflow-runner/.../runner/ContextAssembler.java`

Replaces `buildFullContext` and supports policies:

```java
public enum ContextPolicy { FULL, FOCUSED, ARTIFACTS, CUSTOM }

public static String assemble(WorkflowDefinition def, WorkflowContext ctx,
                             ContextPolicy policy, String task, List<String> customRefs)
```

Behavior:

- `FULL`: previous `buildFullContext` (legacy/backward-compat).
- `FOCUSED`: only `ctx.getContextInclude()` (Node-IDs/Artifact-IDs), plus
  initial prompt and task.
- `ARTIFACTS`: only Artifact-Refs/Links/Summaries.
- `CUSTOM`: only `customRefs`.

Determinism:

- fixed order,
- canonical JSON serialization,
- no timestamps,
- no raw data for `FOCUSED`/`ARTIFACTS`.

### 3.4 `NodeData` Extensions

Pragmatic via the existing `mcpArgs` options bag (as with `compact`), to avoid
schema migrations. Dedicated fields may follow later.

New options:

```json
{
  "contextMode": "focused",
  "contextRefs": ["nodeA", "artifact:123"],
  "postGuard": {
    "condition": "score < 0.8",
    "stateUpdates": { "retry": true, "missingFields": true },
    "injectRefs": ["artifact:retry-data"],
    "nextNode": "agent_retry"
  },
  "injectRefs": ["artifact:input-data"]
}
```

Meaning:

- `contextMode`: `full`, `focused`, `artifacts`, `custom`.
- `contextRefs`: explicit references for `CUSTOM`.
- `postGuard`: deterministic evaluation after an agent run.
- `injectRefs`: artifact refs to include in `contextInclude`.

### 3.5 `guard` and `inject` Nodes

New node types:

- `guard`: deterministic check with `condition`, `onTrue`, `onFalse`,
  `stateUpdates`, `injectRefs`, `nextNode`.
- `inject`: takes `injectRefs` and adds them to `contextInclude`.

`GraphCompiler.VALID_NODE_TYPES` is extended with `guard` and `inject`.

### 3.6 Dynamic Jumps

`WorkflowStepProviderImpl.getNextNodeId` is extended:

```java
String override = (String) ctx.getState("nextNode");
if (override != null && !override.isBlank()) {
    ctx.setState("nextNode", null); // consume
    return override;
}
```

This lets `guard`/`inject`/`decision` set a dynamic jump without an edge
existing in the graph. It closes the gap between static graph and dynamic
state machine.

Loop protection: `LoopDetector` and `attempts` remain active. A jump that
repeats the same node without state change is detected as a loop.

---

## 4. Implementation

### Phase 0 — Context and Artifact Model

1. Create `ArtifactRef`, `ArtifactStore`, `InMemoryArtifactStore`.
2. Extend `WorkflowContext` with artifacts, `contextInclude`, `contextExclude`,
   `lastStructuredResult`.
3. Persistence via `workflowState` keys.
4. Extend `WorkflowContextTest`.

### Phase 1 — Dynamic Context Assembly

5. `ContextAssembler` with policies `FULL`, `FOCUSED`, `ARTIFACTS`, `CUSTOM`.
6. Replace `DefaultNodeDispatcher.buildFullContext` with
   `ContextAssembler.assemble`.
7. `context_agent_executor` uses `contextMode` from `mcpArgs` (default:
   `FOCUSED` if `contextInclude` is non-empty, otherwise `FULL`).
8. `ContextAssemblerTest` with determinism and hygiene.

### Phase 2 — Post-Guard and Dynamic Jumps

9. `guard` and `inject` node types in `GraphCompiler` and
   `DefaultNodeDispatcher`.
10. Extend `getNextNodeId` with `nextNode` override.
11. Optional `postGuard` in agent nodes:
    - store structured result in `lastStructuredResult` after `runAgent`,
    - evaluate condition,
    - set state updates,
    - include `injectRefs` in `contextInclude`,
    - set `nextNode`.
12. Reuse `ConditionEvaluator` for guard conditions.

### Phase 3 — Enforce Context Hygiene

13. Apply `ContextBudgetGate` to focused context.
14. `mcp_call` may store large results as an artifact and return only the
    `ArtifactRef`.
15. `compact` may reduce `contextInclude`.
16. UI: artifact list, context preview, guard editor.

### Phase 4 — Tests and Coverage

17. Unit, integration, and E2E tests (see Section 6).
18. `mvn test` and `mvn -pl engine verify` (≥ 80 %).

---

## 5. Implementation Details

### 5.1 `ContextAssembler`

```java
public final class ContextAssembler {
    private ContextAssembler() {}

    public static String assemble(WorkflowDefinition def, WorkflowContext ctx,
                                 ContextPolicy policy, String task,
                                 List<String> customRefs) {
        // FULL: previous logic
        // FOCUSED: only ctx.getContextInclude()
        // ARTIFACTS: only Artifact-Refs/Links/Summaries
        // CUSTOM: only customRefs
        // always: Initial-Prompt + Task
    }
}
```

Rules:

- `FULL` keeps the previous order and serialization.
- `FOCUSED` contains only referenced node outputs/artifacts.
- `ARTIFACTS` contains no raw data, only `id`, `type`, `summary`, `uri`.
- If `contextInclude` is empty, `FOCUSED` falls back to the last node output
  plus initial prompt (no hard error).
- No silent truncation; on budget overflow, `ContextBudgetGate` throws.

### 5.2 `guard` Node

```java
case "guard" -> {
    Map<String, Object> opts = node.data().mcpArgs() == null ? Map.of() : node.data().mcpArgs();
    String condition = str(opts.get("condition"), "");
    boolean matched = condition.isBlank() || ConditionEvaluator.matches(condition, ctx);

    Map<String, Object> updates = map(opts.get("stateUpdates"));
    updates.forEach(ctx::setState);

    List<String> inject = list(opts.get("injectRefs"));
    inject.forEach(ctx::includeContext);

    String next = matched
            ? str(opts.get("onTrue"), node.data().defaultTarget())
            : str(opts.get("onFalse"), node.data().defaultTarget());
    if (next != null && !next.isBlank()) ctx.setState("nextNode", next);

    ctx.setNodeOutput(node.id(), matched ? "true" : "false");
    return new NodeResult(matched ? "true" : "false", Map.of("matched", matched));
}
```

### 5.3 `inject` Node

```java
case "inject" -> {
    List<String> refs = list(node.data().mcpArgs() == null ? null : node.data().mcpArgs().get("injectRefs"));
    refs.forEach(ctx::includeContext);
    ctx.setNodeOutput(node.id(), "injected:" + refs);
    return new NodeResult("injected:" + refs, Map.of("refs", refs));
}
```

### 5.4 Post-Guard in Agent Nodes

After `runAgent`:

```java
Map<String, Object> guard = map(node.data().mcpArgs() == null ? null : node.data().mcpArgs().get("postGuard"));
if (!guard.isEmpty()) {
    ctx.setLastStructuredResult(structured != null ? structured : Map.of());
    String condition = str(guard.get("condition"), "");
    if (condition.isBlank() || ConditionEvaluator.matches(condition, ctx)) {
        map(guard.get("stateUpdates")).forEach(ctx::setState);
        list(guard.get("injectRefs")).forEach(ctx::includeContext);
        String next = str(guard.get("nextNode"), null);
        if (next != null && !next.isBlank()) ctx.setState("nextNode", next);
    }
}
```

This makes "deterministically evaluate after every agent run" explicit and
traceable, without implicit magic.

### 5.5 `ContextBudgetGate` (focused)

The existing budget gate is applied to the focused context. On overflow:

```text
WorkflowBudgetExceeded(nodeId, estimatedTokens, maxInputTokens,
  "Reduce contextInclude or insert a compact step.")
```

No silent truncation.

---

## 6. Tests

### 6.1 Unit Tests

**New file:** `workflow-runner/.../runner/ArtifactStoreTest.java`

| Test | Expectation |
|---|---|
| `putAndGetRoundTrips` | payload identical |
| `listReturnsRefs` | refs present |
| `removeDeletes` | no longer retrievable |

**New file:** `workflow-runner/.../runner/WorkflowContextSelectionTest.java`

| Test | Expectation |
|---|---|
| `includeAndExcludeAreDeterministic` | order stable |
| `artifactsAreCopied` | no external mutation |
| `resumeRestoresSelection` | restored from `workflowState` |

**New file:** `workflow-runner/.../runner/ContextAssemblerFocusedTest.java`

| Test | Expectation |
|---|---|
| `focusedIncludesOnlySelectedRefs` | unselected outputs absent |
| `artifactsModeIncludesOnlyRefs` | no raw data |
| `customUsesCustomRefs` | only customRefs |
| `fullRemainsBackwardCompatible` | previous behavior unchanged |
| `focusedFallsBackWhenEmpty` | last output + initial prompt |
| `focusedIsDeterministic` | building twice → identical |

**New file:** `workflow-runner/.../runner/GuardNodeTest.java`

| Test | Expectation |
|---|---|
| `trueBranchSetsStateAndNextNode` | state + `nextNode` set |
| `falseBranchWorks` | `onFalse` |
| `injectRefsAdded` | `contextInclude` extended |
| `conditionBlankMatches` | default true |

**New file:** `workflow-runner/.../runner/InjectNodeTest.java`

| Test | Expectation |
|---|---|
| `injectAddsRefs` | `contextInclude` contains refs |
| `injectIsDeterministic` | output stable |

**New file:** `workflow-runner/.../runner/DynamicJumpTest.java`

| Test | Expectation |
|---|---|
| `nextNodeOverrideIsConsumed` | next node from override, then null |
| `loopDetectionPreventsInfiniteJump` | loop detected |

### 6.2 Integration Tests

**New file:** `workflow-runner/.../runner/ContextHygieneIntegrationTest.java`

| Test | Expectation |
|---|---|
| `largeRawDataNotInPrompt` | prompt contains only Artifact-Ref/Summary |
| `focusedContextSmallerThanFull` | token estimate smaller |
| `guardDrivesBranch` | structured result controls flow |

**New file:** `workflow-runner/.../runner/PostGuardIntegrationTest.java`

| Test | Expectation |
|---|---|
| `postGuardOnLowScoreRetries` | `retry=true`, `nextNode=agent_retry` |
| `postGuardInjectsRefs` | refs in `contextInclude` |
| `postGuardSkippedWhenConditionFalse` | no state change |

### 6.3 E2E Tests

**New file:** `quarkus/.../http/DynamicWorkflowE2ETest.java`

| Test | Expectation |
|---|---|
| `agentLowScoreLoopsWithInjectedArtifact` | second agent receives focused context with new ref |
| `contextAgentUsesFocusedContext` | request contains only selected refs |
| `artifactNotLeakedAsRaw` | large payload not in prompt |
| `guardBranchesToDifferentAgent` | different agent is invoked |
| `dynamicJumpWorks` | `nextNode` override is honored |

### 6.4 Existing Tests

- `DefaultNodeDispatcherContextTest` (adapt to `ContextAssembler`)
- `WorkflowContextTest`
- `WorkflowRunJournalTest`
- `ParallelToolExecutionTest`, `SubagentParallelTest`, `ParallelSubagentE2ETest`

### 6.5 Coverage

```bash
cd qoc
mvn -pl engine test
mvn -pl workflow-runner test
mvn -pl quarkus test
mvn test
mvn -pl engine verify
```

Acceptance: all tests green, coverage ≥ 80 %.

---

## 7. Implementation Order

### P0 — Model

1. `ArtifactRef`, `ArtifactStore`, `InMemoryArtifactStore`.
2. `WorkflowContext` extensions.
3. Persistence via `workflowState`.
4. Tests for artifacts and selection.

### P1 — Dynamic Context Assembly

5. `ContextAssembler` with policies.
6. `DefaultNodeDispatcher` uses `ContextAssembler`.
7. `context_agent_executor` with `contextMode`.
8. `ContextAssemblerTest`.

### P2 — Guards and Dynamic Jumps

9. `guard` and `inject` nodes.
10. `getNextNodeId` override.
11. `postGuard` in agent nodes.
12. Tests.

### P3 — Enforce Context Hygiene

13. `ContextBudgetGate` on focused context.
14. `mcp_call` stores large results as an artifact.
15. `compact` reduces `contextInclude`.
16. UI: artifacts, preview, guard editor.

### P4 — Tests and Coverage

17. Unit/integration/E2E.
18. Coverage gate.

---

## 8. Acceptance Criteria

- After every agent run, a deterministic guard can control flow and state.
- Large raw data does not end up in the prompt; only references/links/summaries.
- `ContextAssembler` supports `FULL`, `FOCUSED`, `ARTIFACTS`, `CUSTOM`.
- Dynamic jumps via `nextNode` are possible and loop-protected.
- `context_agent_executor` is no longer necessarily full context.
- Budget gate applies to the focused context.
- All tests green, coverage ≥ 80 %.

---

## 9. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Too many options in the options bag | Unwieldy | dedicated `NodeData` fields later |
| Dynamic jumps create loops | Infinite loop | `attempts`, `LoopDetector`, max steps |
| Artifact store grows | Memory/DB | TTL, cleanup, size limit |
| Focused context loses information | Worse results | `FULL` mode as fallback, tests |
| UI complexity | Hard to use | defaults, preview, validation |

---

## 10. Conclusion

The correction shifts the emphasis from "always full context" to
"deterministically controlled, focused context". The agent remains the
execution tool; the workflow becomes a dynamic state machine. Artifacts and
links replace full text in the prompt. This closes the gap between rigid
graphs and uncontrolled agents, without introducing a third loop type.