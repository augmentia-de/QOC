# Implementation Plan: LangChain4j TypeSafe Decision Model Integration

Status: Plan (2026-11-18)
Related: `workflow-runner/.../runner/DefaultNodeDispatcher.java`, `workflow-runner/.../validation/GraphCompiler.java`,
`workflow-runner/.../model/WorkflowDefinition.java`, `docs/plans/verify-on-stop-guards.md` (low_margin mechanism)

## 1. Problem

Workflows can currently generate text (`agent`, `llm_eval`, `llm_summarize`) and
branch deterministically (`decision`, `guard`), but there is **no node type for
typed decisions** (classification/routing/gating/grading with probabilities).
Routing decisions are based on string templates or LLM text, not on calibrated
probabilities. The existing `guard` node already has a `low_margin` path
(Correction-Rounds limit) tailored to decision-model margins, but no producer
delivers these values.

LangChain4j has offered the `@Experimental` `DecisionModel` API since 1.21 and
the `TypeSafeDecisionModel` implementation (TypeSafe System One, `POST /v1/systemone`),
which delivers exactly these typed responses (YesNo/Choice/Scale with probabilities).

## 2. Target Design

A new workflow node type **`classify`** that:

1. Evaluates an input (template-resolved) against a decision model.
2. Answers typed questions (yesno/choice/scale) in **a single** call.
3. Writes results (chosen values, probabilities, margins) to the workflow state
   (`ctx.setState`, `ctx.setNodeOutput`).
4. Sets `low_margin=true` when margin is low (`margin < threshold`), so the
   existing `guard` mechanism (Correction-Rounds) kicks in.
5. Is configurable to fail-open or fail-closed.

The engine (`engine.core`) remains untouched (pure-Java boundary, no use case).

## 3. Dependencies / Versions

- `workflow-runner` uses **no** Quarkus BOM → free version choice possible.
- **No new Maven dependency:** the implementation uses `java.net.http.HttpClient` (JDK 21)
  and the existing Jackson `ObjectMapper` (`WorkflowJson`). This keeps `workflow-runner`
  free of LangChain4j and its version drift. The originally planned
  `langchain4j-typesafe` dependency was **not** added.
- The Quarkus app (`quarkus/`) pins `langchain4j-core 1.19.3` via BOM → **do not touch**;
  the DecisionModel client lives exclusively in the Workflow Runner.
- API key via env `TYPESAFE_API_KEY` (fallback: Node config `apiKey`).
- Model name defaults to `jev-latest` for dev only; production pins a version
  (e.g. `jev-1.13.0`) via Node config or env `TYPESAFE_MODEL_NAME`.
- Protocol: `POST <baseUrl>/v1/systemone` (default `https://api.typesafe.ai`),
  header `Authorization: Bearer <apiKey>`, body `{model, state, questions}` →
  `{model, answers, usage}`. Matches 1:1 what LangChain4j's `TypeSafeMapper`
  serializes.

## 4. Data Model

### 4.1 Node Type

New entry in `GraphCompiler.VALID_NODE_TYPES`: `"classify"`.

No new field in `NodeData` required: configuration runs through `mcpArgs`
(options bag), analogous to `guard`/`compact`/`rest_call`. This keeps schema and
UI serialization compatible.

### 4.2 Node Config (mcpArgs)

```json
{
  "type": "classify",
  "data": {
    "title": "Route ticket",
    "mcpArgs": {
      "modelName": "jev-1.13.0",
      "input": "${input}",
      "questions": [
        { "name": "team", "type": "choice", "text": "Which team?",
          "options": { "billing": "Payments, payouts", "support": "Product usage" } },
        { "name": "urgent", "type": "yesno", "text": "Needs attention today?" }
      ],
      "thresholds": { "urgent": 0.8, "margin": 0.2 },
      "outputMapping": { "team": "ticketTeam", "urgent": "urgent" },
      "failMode": "open"
    }
  }
}
```

Fields:
- `modelName` (String, optional): Decision-Model name. Fallback Env/`jev-latest`.
- `baseUrl` (String, optional): for OpenRouter/self-hosted. Default TypeSafe.
- `input` (String or Map): template-resolved. If String → `DecisionRequest.input(String)`;
  if Map → `input(Map)`.
- `questions` (List<Map>): each with `name`, `type` ∈ {yesno, choice, scale}, `text`,
  plus `options` (Map) for choice, `levels` (List<String>) for scale,
  `yesWhen`/`noWhen` (optional) for yesno.
- `thresholds` (Map): `margin` (double, default 0.2) for Choice, per-question
  thresholds (e.g. `urgent: 0.8` for YesNo).
- `outputMapping` (Map questionName → stateKey): writes chosen value into state.
  Default: question name as state key.
- `failMode` ∈ {open, closed}: behavior on error/refusal.
- `apiKey` (String, optional): override for Env.

### 4.3 Output (NodeResult)

- `text`: compact summary, e.g. `classify: team=billing (0.91), urgent=yes (0.97)`.
- `structured`: Map with per question `{ value, probabilities, margin, probability }`.
- State: chosen value under the mapped key for each question; plus
  `classifyResult` (full map) and `low_margin` (boolean).

## 5. Implementation

### 5.1 Dependency

`workflow-runner/pom.xml`:
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-typesafe</artifactId>
    <version>1.22.0-beta32</version>
</dependency>
```

### 5.2 GraphCompiler

Extend `VALID_NODE_TYPES` with `"classify"`.

### 5.3 DefaultNodeDispatcher

New case `"classify"` in `dispatchImpl`, analogous to `llm_eval`/`rest_call`:

1. Read `mcpArgs` (null-safe).
2. Resolve `input` template (`TemplateResolver.resolveStrict` or `resolveToObject`).
3. Build `TypeSafeDecisionModel` (apiKey from Config/Env, modelName, baseUrl optional,
   timeout 60s, maxRetries 2).
4. Build `DecisionRequest` from Config.
5. Call `model.decide(request)` (synchronous).
6. Map result → `NodeResult` + State.
7. Margin check → `low_margin`.
8. Error handling per `failMode`.

### 5.4 Client Class (testable)

New class `workflow-runner/.../runner/TypeSafeDecisionClient` (or static helpers
inside the Dispatcher). Encapsulates:
- `buildModel(config)` → `TypeSafeDecisionModel`
- `buildRequest(config, input)` → `DecisionRequest`
- `execute(...)` → `DecisionResponse`
- `mapResponse(...)` → Map

This allows testing the client in tests with a fake model (MockDecisionModel)
or a local HTTP server. `TypeSafeDecisionModel` implements `DecisionModel` →
for unit tests `dev.langchain4j.model.decision.MockDecisionModel` can be used
(if available) or a custom fake.

### 5.5 Margin/Threshold Logic

- For each `ChoiceQuestion`: if `answer.margin() < thresholds.margin` → `low_margin=true`.
- For `YesNoQuestion`: if `probability` is between `(1-threshold)` and `threshold`
  (uncertain) → optionally `low_margin=true` (configurable via `thresholds.uncertainBand`).
- State `low_margin` is set so the existing `guard` node reacts to the
  `low_margin` condition.

### 5.6 Fail-Mode

- `open` (default): on error/refusal → NodeResult with error text, workflow
  continues (like `rest_call` error).
- `closed`: on error/refusal → `IllegalStateException` (aborts workflow),
  analogous to Agent node.

## 6. Files

### New
- `workflow-runner/.../runner/TypeSafeDecisionClient.java`
- `workflow-runner/src/test/.../runner/ClassifyNodeTest.java`

### Changed
- `workflow-runner/pom.xml` (Dependency)
- `workflow-runner/.../validation/GraphCompiler.java` (VALID_NODE_TYPES)
- `workflow-runner/.../runner/DefaultNodeDispatcher.java` (case "classify")

### Unchanged
- `engine/` (pure-Java boundary)
- `quarkus/` (BOM/versions)
- `WorkflowDefinition.java` (mcpArgs suffices)

## 7. Implementation Order

1. **Day 1:** Dependency + GraphCompiler entry + `TypeSafeDecisionClient`
   (model build + request build + response mapping) + unit tests with fake model.
2. **Day 1-2:** Dispatcher case `"classify"` + state/output mapping + margin logic.
3. **Day 2:** Fail-mode + template resolution + tests (GraphCompiler, Dispatcher).
4. **Day 2-3:** Docs (`docs/CONFIG.md` or new `docs/decision-models.md`),
   examples in `docs/Agent-Def-Example.md` style.
5. **Day 3:** Manual end-to-end test against TypeSafe (with API key) + tuning.

## 8. Risks

- **Experimental API:** LangChain4j 1.22.0-beta32; API may change.
  Mitigation: client class encapsulates all LangChain4j types; on update only the client needs adjustment.
- **Version drift:** workflow-runner uses core 1.22.0, quarkus uses 1.19.3.
  No shared classpath in the build (separate modules) → no conflict.
  On later Quarkus BOM updates, check version alignment.
- **Network/timeout:** TypeSafe calls can take 15s+60s+retries.
  Mitigation: configurable timeout, fail-mode, workflow step timeout.
- **Calibration:** thresholds are model/version dependent. Document that
  thresholds must be recalibrated per model.
- **Fail-closed for gating:** abuse filters should use `failMode=closed`
  (documentation hint).

## 9. Open Questions

1. Should `classify` also be visible in the Quarkus UI as a node type
   (ReactFlow node renderer), or initially backend only?
2. Should the decision-model client be exposed in Quarkus as a CDI bean
   (for HTTP endpoint `/classify`), or workflow-intern only?
3. Pin default model version (`jev-1.13.0`) or `jev-latest` for dev?
4. Scaling: multiple questions per call (recommended) vs. one call per question
   (more expensive) – default: all questions in one call.

## 10. Acceptance Criteria

- [ ] `mvn -q test` in `workflow-runner/` green.
- [ ] `GraphCompiler` accepts `classify` nodes, rejects invalid config.
- [ ] Unit test with fake `DecisionModel` checks YesNo/Choice/Scale mapping,
      state writes, `low_margin` setting, fail-open/closed.
- [ ] Manual run against TypeSafe (with API key) returns typed responses.
- [ ] No changes in `engine/` or `quarkus/`.

## 11. Decisions (2026-11-18)

### 11.1 Pure HTTP instead of LangChain4j

The implementation uses **pure HTTP** (`java.net.http.HttpClient`) instead of
`dev.langchain4j.model.typesafe.TypeSafeDecisionModel`. Rationale:

- `workflow-runner` stays free of LangChain4j and its version drift
  (Quarkus BOM pins `langchain4j-core 1.19.3`, TypeSafe would need ≥ 1.21).
- No `@Experimental` API in the production path.
- The System One protocol is small and stable; the JSON format matches 1:1
  what LangChain4j's `TypeSafeMapper` serializes (verified via bytecode).
- Tests use a local `com.sun.net.httpserver.HttpServer` (no mocking).

LangChain4j can later be added as an alternative adapter behind the same
`TypeSafeDecisionClient` facade if desired.

### 11.2 Logic stays in the Workflow Runner (not in the Engine)

The decision-model logic is **not** moved into `engine.core`. Rationale:

1. **Use case:** `engine.core` is the session engine (chat/tool loop, compaction,
   revert). `DecisionModel` is a workflow building block (classification/routing
   before agent execution). The engine has no consumer (triage runs via
   `ProviderAdapter`, permission is rule-based).
2. **Pure-Java boundary:** a port without a consumer violates YAGNI and bloats `QocEngine`.
3. **Consistency:** `workflow-runner` uses the engine as a library and already has
   HTTP infrastructure (`rest_call`, `java.net.http`).

**If an engine consumer arises later** (e.g. `/classify` endpoint in Quarkus,
triage on DecisionModel), the right package is:

- Interface: `engine.core.contract.decision.DecisionModel` (analogous to
  `core.contract.provider.ProviderAdapter`) **or**
  `engine.core.port.world.DecisionModelClient` (for external service).
- Adapter (pure Java HTTP): `engine.adapter.decision.HttpDecisionModel`
  (or `engine.runtime.provider.client` style).
- Wiring in `QocEngine` only once a consumer exists (factory method,
  not facade field).

Until then the client stays in `workflow-runner` and can be moved to
`engine.adapter.decision` on demand without changing node semantics.

### 11.3 llm_eval/llm_summarize replaced by agent files

The specialized node types `llm_eval` and `llm_summarize` were removed.
Instead, two reusable agent definitions exist as Markdown files:

- `.opencode/agents/llm-eval.md` (system prompt: Eval/JSON)
- `.opencode/agents/llm-summarize.md` (system prompt: Summarize)

These are loaded via `AgentMarkdownLoader` (Quarkus) and are available as
agent refs `llm-eval` / `llm-summarize`. In workflows, use an `agent` node
with `agentRef: "llm-eval"` or `"llm-summarize"` instead.

Removed from: `GraphCompiler.VALID_NODE_TYPES`, `DefaultNodeDispatcher` (cases),
`WorkflowContractValidator`, UI (`nodeRegistry`, `workflowNodes`, `EditorPage` palette).
