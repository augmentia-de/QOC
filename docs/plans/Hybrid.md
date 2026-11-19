# Hybrid Workflow/Agent Execution — Implementation Plan

Date: 2026-11-12  
Status: Plan (no implementation)  
Reference: `docs/engineering/ReAct-Workflow.md`, `docs/engineering/Additional-Nodes.md`

## 1. Goal and Scope

The workflow runner should support a hybrid mode:

- **Deterministic nodes** (`start`, `decision`, `fork`, `join`, `loop`, `end`,
  `mcp_call`, …) remain unchanged and run without an LLM.
- **Normal `agent` nodes** keep their previous behavior: their own
  agent system prompt, minimal input (predecessor output).
- **New node `context_agent_executor`** runs an agent with the
  **entire previous workflow context**:
  - optionally a shared, byte-identical workflow system prompt
    (`WorkflowDefinition.systemPrompt`) for prompt caching,
  - otherwise the agent system prompt,
  - then the complete workflow context as a user message,
  - then a small current task ("Task").
- After the complete agent loop (including all tool calls), **only the
  agent result** is taken into the workflow context. The intermediate
  child-session context (tool calls, intermediate messages) is discarded.
- **No summarize node** and **no runtime compaction** in the
  `context_agent_executor` run. The context size is estimated up front; if it
  is insufficient, the workflow author must add a compact step
  (e.g. `llm_summarize`) beforehand.

Not part of this plan:

- Changes to `SubagentTool`: agents invoked via `SubagentTool` still
  receive **no** shared context and run isolated.
- A separate phase field: the phase is carried along in the initial prompt /
  context.
- A `contextTemplate`: the full context is always passed.

## 2. Flow of the New Node

```
Workflow-Kontext (WorkflowContext)
  ├─ initialPrompt            (workflow input, contains phase A/B/C)
  ├─ nodeOutputs              (deterministisch in def.nodes()-Reihenfolge)
  └─ workflowState            (sorted keys)
            │
            ▼
  buildFullContext(def, ctx)  →  fullContext (String)
            │
            ▼
  systemPrompt = sharedWorkflowPrompt.isBlank()
                   ? agent.systemPrompt
                   : sharedWorkflowPrompt
            │
            ▼
  userPrompt = "## Previous context\n" + fullContext
             + "\n\n## Current task\n" + task
            │
            ▼
  childSession = engine.createSession(...)
  engine.runPrompt(childSession, name, userPrompt, config, true, null, null,
                   principal, overrides(compactionEnabled=false))
            │
            ▼
  ctx.setNodeOutput(node.id(), structured != null ? structured : result.text())
```

Important: `systemPrompt` stays stable in both cases. The full context
always lives in the user message, never in the system prompt. As a result
the system prompt is cacheable (shared) or likewise stable (agent system
prompt).

## 3. Data Model

### 3.1 `WorkflowDefinition.systemPrompt`

**File:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/model/WorkflowDefinition.java`

New last record field `String systemPrompt` and default in the
compact constructor:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(
        String id,
        String name,
        String version,
        String status,
        List<Node> nodes,
        List<Edge> edges,
        List<Agent> agents,
        String ownerId,
        String systemPrompt
) {
    public WorkflowDefinition {
        // ... bestehende Defaults ...
        if (systemPrompt == null) systemPrompt = "";
    }
}
```

- Jackson: old JSON files without `systemPrompt` remain loadable
  (`@JsonIgnoreProperties(ignoreUnknown = true)`, missing field → `null` →
  `""`).
- `NodeData` remains **unchanged**. In particular there is no
  `contextTemplate`, no `phase` field, and no new required fields.

### 3.2 `WorkflowContext.initialPrompt`

**File:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/WorkflowContext.java`

```java
/** Original workflow input; contains phase information. */
private String initialPrompt;

public String getInitialPrompt() {
    return initialPrompt;
}

public void setInitialPrompt(String initialPrompt) {
    this.initialPrompt = initialPrompt;
}

/** Bestehender Restore-Pfad bleibt erhalten. */
public void restore(Map<String, Object> nodeOutputs,
                    Map<String, Object> workflowState,
                    String currentNodeId) {
    // unchanged
}

/** Neuer Restore-Pfad inkl. Initial-Prompt. */
public void restore(Map<String, Object> nodeOutputs,
                    Map<String, Object> workflowState,
                    String currentNodeId,
                    String initialPrompt) {
    restore(nodeOutputs, workflowState, currentNodeId);
    this.initialPrompt = initialPrompt;
}
```

The authoritative persistence already exists: `WorkflowExecutor` writes the
input via `sink.runStarted(..., context)` into the journal, and
`WorkflowRunSnapshot.input()` delivers it on resume. `CheckpointStore` is not
wired up in the current runner; the extension there is optional
(see 3.4).

### 3.3 `ExecutionOverrides.compactionEnabled`

**File:** `engine/src/main/java/de/augmentia/oc/engine/runtime/QocEngine.java`

Extend the record by one field and keep a backward-compatible 6-arg
constructor:

```java
public record ExecutionOverrides(
        AgentProfile agentProfile,
        ModelInfo modelInfo,
        Step step,
        ToolSnapshot toolSnapshot,
        ContextBuilder contextBuilder,
        SkillSelection skillSelection,
        boolean compactionEnabled
) {
    /** Backward compatible: compaction enabled by default. */
    public ExecutionOverrides(AgentProfile agentProfile,
                              ModelInfo modelInfo,
                              Step step,
                              ToolSnapshot toolSnapshot,
                              ContextBuilder contextBuilder,
                              SkillSelection skillSelection) {
        this(agentProfile, modelInfo, step, toolSnapshot, contextBuilder,
             skillSelection, true);
    }
}
```

Compaction gate in `runPrompt` (currently line 817):

```java
if (!workflowMode
        && (overrides == null || overrides.compactionEnabled())
        && result instanceof de.augmentia.oc.engine.runtime.step.StepResult.NeedsCompaction) {
    // bestehender Compact-and-Retry-Block
}
```

This keeps the behavior of all existing callers unchanged
(`overrides == null` ⇒ compaction active), and the new node can disable it
selectively.

### 3.4 `CheckpointStore.initialPrompt` (optional, consistency)

**File:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/store/CheckpointStore.java`

- `save(...)`: `data.put("initialPrompt", ctx.getInitialPrompt());`
- `load(...)`: `ctx.setInitialPrompt((String) data.get("initialPrompt"));`

Even though the runner does not currently use the store, the checkpoint
format stays complete this way.

## 4. Dispatcher: new node `context_agent_executor`

**File:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/DefaultNodeDispatcher.java`

### 4.1 New `switch` case

Insert directly after the `case "agent"` block (currently line 82):

```java
case "context_agent_executor" -> {
    String agentRef = node.data().agentRef();
    WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);

    // Eigener Registry-Name pro Knoten, damit der Systemprompt dieses
    // Knotens nicht durch eine bereits registrierte Agent-Config
    // (resolveAgentConfig: first registration wins) is overridden.
    String prefixedName = "wf:ctx:" + node.id();

    String fullContext = buildFullContext(def, ctx);

    String shared = def.systemPrompt();
    String agentSp = agentDef == null ? "" : agentDef.systemPrompt();
    String systemPrompt = (shared == null || shared.isBlank()) ? agentSp : shared;

    String task = node.data().title();
    if (task == null || task.isBlank()) task = node.data().defaultTarget();
    if (task == null || task.isBlank()) task = "Continue with the next step.";

    String userPrompt = "## Previous context\n" + fullContext
            + "\n\n## Current task\n" + task;

    QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName, systemPrompt);

    // Kein Runtime-Compaction in diesem Lauf.
    QocEngine.ExecutionOverrides overrides = new QocEngine.ExecutionOverrides(
            null, null, null, null, null, null, false);

    return runAgent(node, prefixedName, userPrompt, config, ctx, principal, overrides);
}
```

Rationale for `wf:ctx:<nodeId>`:

- `QocEngine.resolveAgentConfig(name, config)` returns an already registered
  config and ignores the one passed in. If the node used the same name as a
  normal `agent` node, the shared prompt could be lost.
- A separate name per node keeps this node's model/tools/system prompt stable
  and isolated.

### 4.2 `runAgent` overload

The existing `runAgent` signature remains; an overload with
`ExecutionOverrides` is added:

```java
private NodeResult runAgent(WorkflowDefinition.Node node, String prefixedName,
                            String prompt, QocEngine.AgentConfig config,
                            WorkflowContext ctx,
                            de.augmentia.oc.engine.core.model.Principal principal) {
    return runAgent(node, prefixedName, prompt, config, ctx, principal, null);
}

private NodeResult runAgent(WorkflowDefinition.Node node, String prefixedName,
                            String prompt, QocEngine.AgentConfig config,
                            WorkflowContext ctx,
                            de.augmentia.oc.engine.core.model.Principal principal,
                            QocEngine.ExecutionOverrides overrides) {
    if (principal == null) principal = Principal.anonymous();
    SessionId child = engine.createSession(prefixedName, principal.id(), "workspace");
    EngineResult result = engine.runPrompt(
            child, prefixedName, prompt, config, true, null, null, principal, overrides);
    // ... existing error/pause check ...
    Map<String, Object> structured = StructuredOutputConverter.toMap(result.structured());
    ctx.setNodeOutput(node.id(), structured != null ? structured : result.text());
    return new NodeResult(result.text(), structured, child.value());
}
```

Important: Only `result.text()` / `result.structured()` are written into the
workflow context. Tool calls and intermediate messages of the child session
are not carried over.

### 4.3 `toAgentConfig` overload

**File:** same file, currently line 266.

```java
private QocEngine.AgentConfig toAgentConfig(WorkflowDefinition.Agent a,
                                            String prefixedName,
                                            String systemPromptOverride) {
    QocEngine.AgentConfig base = toAgentConfig(a, prefixedName);
    return new QocEngine.AgentConfig(
            base.name(), base.model(), base.temperature(), base.maxTokens(),
            base.topP(), systemPromptOverride, base.tools(),
            base.guardrailsInput(), base.guardrailsOutput(), base.hooks(),
            base.jsonOutput(), base.responseSchema());
}
```

### 4.4 `buildFullContext`

Package-private `static`, so it can be tested directly:

```java
static String buildFullContext(WorkflowDefinition def, WorkflowContext ctx) {
    StringBuilder sb = new StringBuilder();

    sb.append("## Initial prompt\n");
    String initial = ctx.getInitialPrompt();
    sb.append(initial == null || initial.isBlank() ? "(none)" : initial).append("\n\n");

    sb.append("## Node outputs\n");
    for (WorkflowDefinition.Node n : def.nodes()) {
        Object out = ctx.getNodeOutput(n.id());
        if (out == null) continue;
        sb.append("### ").append(n.id()).append(" (").append(n.type()).append(")\n");
        sb.append(renderValue(out)).append("\n\n");
    }

    sb.append("## Workflow state\n");
    ctx.getWorkflowState().entrySet().stream()
            .filter(e -> !"lastResult".equals(e.getKey())) // steht bereits in den Node-Outputs
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                    .append(renderValue(e.getValue())).append('\n'));

    return sb.toString().trim();
}

private static String renderValue(Object value) {
    if (value == null) return "";
    if (value instanceof String s) return s;
    try {
        return WorkflowJson.mapper().writeValueAsString(value);
    } catch (Exception e) {
        return String.valueOf(value);
    }
}
```

- **Determinism:** node outputs in `def.nodes()` order (not in `HashMap`
  iteration order), state keys sorted. This keeps the prompt stable across
  runs (cache).
- `lastResult` is omitted from the state because the last node output is
  already included.
- Add import: `de.augmentia.qoc.workflowrunner.store.WorkflowJson`.

### 4.5 Size estimation (no compaction in the run)

Estimate before `runPrompt` and only warn on overflow:

```java
private static final int CHARS_PER_TOKEN = 4;
private static final int CONTEXT_BUDGET_TOKENS = 96_000;

private static int estimateTokens(String text) {
    return text == null ? 0 : text.length() / CHARS_PER_TOKEN;
}
```

In the case:

```java
int estimated = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
if (estimated > CONTEXT_BUDGET_TOKENS) {
    LOG.warn("context_agent_executor {}: estimated {} tokens exceed budget {}. "
            + "Insert a compact step before this node if the run fails.",
            node.id(), estimated, CONTEXT_BUDGET_TOKENS);
}
```

Deliberately **no** automatic compaction: `compactionEnabled=false` switches
off the runtime path; pre-compaction is the workflow author's job (e.g.
`llm_summarize` node before it). Later an optional `compactContext(...)`
helper can be added without changing this node's semantics.

## 5. Executor, Expander, Resource, Defaults

### 5.1 `WorkflowExecutor`

**File:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/WorkflowExecutor.java`

- Owner stamping (line 93): `def.systemPrompt()` as the last argument.
- `run(...)` (line 182) sets the initial prompt centrally for start and
  resume:

```java
private WorkflowExecution run(SessionId session, String runId,
                              WorkflowDefinition definition, WorkflowContext ctx,
                              String input, WorkflowEventSink sink,
                              boolean storePrompt,
                              Principal principal) {
    // ...
    ctx.setInitialPrompt(input == null ? "" : input);
    // ... engine.runPrompt(...)
}
```

This ensures `ctx.getInitialPrompt()` is populated after `executeRun` and
after `resume` (`snapshot.input()`).

### 5.2 `SubWorkflowExpander`

**File:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/SubWorkflowExpander.java`, line 53

```java
return new WorkflowDefinition(
        wf.id(), wf.name(), wf.version(), wf.status(),
        flatNodes, rewrittenEdges, agents, wf.ownerId(), wf.systemPrompt()
);
```

Note: the **parent** workflow's shared prompt remains authoritative.
Sub-workflows only bring their own prompt when executed standalone; when
inlined, the parent prompt is used. This is intended, because the parent
workflow's context keeps running.

### 5.3 `WorkflowDefaults`

**File:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/store/WorkflowDefaults.java`

- `create(...)` (line 24): `..., ""`
- `withId(...)` (line 33): `..., definition.systemPrompt()`

### 5.4 `WorkflowResource`

**File:** `quarkus/src/main/java/de/augmentia/oc/engine/http/WorkflowResource.java`

- Line 71 (`createWorkflow`): `..., def.systemPrompt()`
- Line 99 (`saveWorkflow`): `..., def.systemPrompt()`

When saving an existing workflow the shared prompt is preserved this way;
when creating one it is `""`.

## 6. Validation

**File:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/validation/GraphCompiler.java`

- Extend `VALID_NODE_TYPES` (line 14) with `"context_agent_executor"`.
- Also check the mandatory agent ref for the new type. Recommendation:
  introduce a set:

```java
private static final Set<String> AGENT_NODE_TYPES =
        Set.of("agent", "context_agent_executor");
```

and switch the two `"agent".equals(n.type())` checks (lines 56 and 80) to
`AGENT_NODE_TYPES.contains(n.type())`. This means a
`context_agent_executor` without `agentRef` is invalid, and the `agentRef`
must exist in `agents[]` or in the external agent store.

## 7. UI

### 7.1 Types

**File:** `workflow-ui/src/types/workflow.ts`

```ts
export interface WorkflowDefinition {
  id: string;
  name: string;
  version: string;
  status: string;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  agents: Agent[];
  systemPrompt?: string;   // NEU
}
```

`NodeData` remains unchanged (no template, no phase field).

### 7.2 Graph handles and connection rules

**File:** `workflow-ui/src/lib/workflowGraph.ts`

- `SINGLE_SOURCE_HANDLES`: `context_agent_executor: 'out'`
- `SINGLE_TARGET_HANDLES`: `context_agent_executor: 'in'`

**File:** `workflow-ui/src/lib/connectionRules.ts`

- `SINGLE_INCOMING`: add `'context_agent_executor'`
- `SINGLE_OUTGOING`: add `'context_agent_executor'`

### 7.3 Node component

**New:** `workflow-ui/src/components/ContextAgentExecutorNode.tsx`

Modelled on `AgentNode.tsx`. Its own color (e.g.
`bg-cyan-700 border-cyan-400`), title `data.title || 'Context Agent'`,
subtitle `data.agentRef || 'no agent'`, ports `in`/`out`, optional hint
"full context".

**File:** `workflow-ui/src/pages/GraphEditor.tsx`

- Add the import.
- `nodeTypes` (line 43): `context_agent_executor: ContextAgentExecutorNode`
- Palette (from line 362):

```ts
{ label: '+ Context Agent', type: 'context_agent_executor',
  color: 'bg-cyan-700', data: { title: 'Context Agent', agentRef: '' } },
```

### 7.4 Property panel

**File:** `workflow-ui/src/components/PropertyPanel.tsx`

Add a new block analogous to the `agent` block (line 649):

```tsx
{selectedNode.type === 'context_agent_executor' && (
  <div className="mb-4 border-t border-gray-700 pt-4">
    <h4 className="mb-2 text-sm font-semibold">Context Agent Executor</h4>
    <div className="mb-3">
      <FieldLabel>Agent-Ref</FieldLabel>
      <select
        value={agentRef}
        onChange={(event) => loadAgent(event.target.value)}
        className="flex-1 rounded bg-gray-700 px-2 py-1 text-sm"
      >
        <option value="">-- neu --</option>
        {agents.map((agent) => (
          <option key={agent.id} value={agent.id}>
            {agent.id} ({agent.name})
          </option>
        ))}
      </select>
    </div>
    <p className="text-xs text-gray-500">
      Passes the full prior workflow context as a user message.
      Der Shared-Systemprompt wird aus den Workflow-Einstellungen verwendet.
    </p>
  </div>
)}
```

The existing `applyBuildAgent`/copy logic is bound to `type === 'agent'`;
not needed for the new type.

### 7.5 Editing the workflow system prompt

**File:** `workflow-ui/src/pages/WorkflowDetail.tsx`

The shared prompt is a workflow property, not a node field. Add a textarea
above the graph (or as a collapsible block):

```tsx
<div className="rounded border border-gray-700 bg-gray-800 p-3">
  <label className="mb-1 block text-xs font-semibold text-gray-400">
    Workflow-Systemprompt (shared, cache-stabil)
  </label>
  <textarea
    value={workflow.systemPrompt ?? ''}
    onChange={(e) => {
      const updated = { ...workflow, systemPrompt: e.target.value };
      setWorkflow(updated);
      setJsonText(JSON.stringify(updated, null, 2));
    }}
    className="h-24 w-full rounded bg-gray-900 p-2 font-mono text-xs"
    placeholder="Optional: shared system prompt for all context_agent_executor nodes"
  />
</div>
```

`save()` already takes over `workflow.systemPrompt`, because the entire
workflow object is saved.

## 8. Tests

### 8.1 New/adjusted Java tests

| Test | File | Content |
|---|---|---|
| `buildFullContext` | new: `workflow-runner/src/test/java/de/augmentia/qoc/workflowrunner/runner/DefaultNodeDispatcherContextTest.java` | Initial prompt, node outputs in `def.nodes()` order, sorted state keys, `lastResult` not duplicated, Map/List as JSON |
| Shared prompt | same | `resolveSystemPrompt` returns the workflow prompt; user prompt contains `## Previous context` and `## Current task` |
| Fallback | same | without shared prompt: agent system prompt stays system; context in user message |
| Determinism | same | same context ⇒ identical string (cache stability) |
| `initialPrompt` | new/extended: `WorkflowContextTest` | set/get, `restore(..., initialPrompt)` |
| JSON compatibility | new/extended: `WorkflowDefinitionJsonTest` | old JSON without `systemPrompt` ⇒ `""` |
| `ExecutionOverrides` | `engine/src/test/java/de/augmentia/oc/engine/runtime/QocEngineTest.java` | 6-arg constructor ⇒ `compactionEnabled() == true`; 7-arg with `false` |
| GraphCompiler | new/extended: `GraphCompilerTest` | `context_agent_executor` valid; without `agentRef` invalid |
| Store contract | `WorkflowStoreContractTest.java`, line 83 | extend constructor with `""` |

Note on dispatcher tests: `DefaultNodeDispatcher` needs a `QocEngine`. For
pure context/prompt tests, make the helpers `buildFullContext(...)` and
`resolveSystemPrompt(...)` package-private static and test them directly. A
full dispatch test can be added with a lean `QocEngine` test instance
(in-memory stores, fake provider); it is not a prerequisite for the first
implementation.

### 8.2 UI

- TypeScript build (`npm run build` or the existing lint/build task).
- Manual: add node, choose agent, save, reload; JSON contains `systemPrompt`
  and `type: "context_agent_executor"`.

## 9. Backward Compatibility and Migration

- `WorkflowDefinition` is a Jackson record with
  `@JsonIgnoreProperties(ignoreUnknown = true)`. Old definitions without
  `systemPrompt` still load (`""`).
- All existing workflows keep the behavior of normal `agent` nodes; the new
  node is only activated on explicit use.
- `ExecutionOverrides` keeps the 6-arg constructor; existing callers and
  tests stay compilable.
- `NodeData` remains unchanged; no migration logic needed.

## 10. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Context too large | Provider error, run fails | Estimate size up front and warn; workflow author adds `llm_summarize` before it; `compactionEnabled=false` prevents silent runtime compact |
| Cache not shared | Shared prompt brings no cache benefit | System prompt is byte-identical; cache affinity is session-based (`CacheAffinity`). For real prefix reuse the child session would have to inherit the parent affinity — documented as a follow-up optimization, not part of this plan |
| Registry collision | Wrong system prompt | Separate name `wf:ctx:<nodeId>` per node |
| Child-session context grows | Memory/journal | Only the result is carried over; the child session is kept for audit but not used in follow-up prompts |
| Sub-workflow shared prompt | Wrong prompt | On inlining the parent prompt wins (`wf.systemPrompt()` is propagated) |
| Resume without initial prompt | Context incomplete | `run(...)` sets `initialPrompt` from `input`; `snapshot.input()` delivers it on resume |
| Context order non-deterministic | Cache miss | `def.nodes()` order + sorted state keys |

## 11. Implementation Order

1. **Model:** `WorkflowDefinition.systemPrompt` + all 6 constructor callers
   (`WorkflowResource` 71/99, `WorkflowDefaults` 24/33, `SubWorkflowExpander`
   53, `WorkflowExecutor` 93, `WorkflowStoreContractTest` 83).
2. **Context:** `WorkflowContext.initialPrompt` + `WorkflowExecutor.run(...)`
   sets it; optional `CheckpointStore`.
3. **Engine:** `ExecutionOverrides.compactionEnabled` + compaction gate +
   test.
4. **Dispatcher:** new case, `runAgent` overload, `toAgentConfig` overload,
   `buildFullContext`, `estimateTokens`, `renderValue`.
5. **Validation:** `GraphCompiler`.
6. **UI:** types, handles, node component, palette, property panel,
   workflow system prompt editor.
7. **Tests:** helper tests, context/JSON/overrides/compiler tests,
   store contract.
8. **Build:** `cd qoc && mvn test` (or `mvn -pl workflow-runner -am test`),
   then UI build.

## 12. Open Points (decided)

| Question | Decision |
|---|---|
| Phase field? | No. The phase lives in `initialPrompt` and thus in the full context. |
| `contextTemplate`? | No. Always the full context. |
| Summarize node? | No. No automatic summarize; optional preceding `llm_summarize`. |
| Compaction in the run? | No. `compactionEnabled=false`; estimate size up front. |
| Subagent shared context? | No. `SubagentTool` stays isolated. |
| Shared prompt fallback? | Agent system prompt stays system; context as user message. |
| Result persistence? | Only `result.text()`/`result.structured()` as `nodeOutput`. |