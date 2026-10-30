# decision-gate – LangChain4j DecisionModel as a QOC Gate

> **Status:** Example / prototype · 2026-10-30
> **Depends on:** `dev.langchain4j:langchain4j-core:1.22.0` (experimental `DecisionModel` API)
> and `dev.langchain4j:langchain4j-typesafe:1.22.0-beta32` (System One client)
> **Related:** `docs/context-build.md`, `docs/Understand.md`

---

## What this module shows

QOC currently sends every permitted tool and every listed skill to the model on
every turn. For small catalogues that is fine; for large catalogues it wastes
tokens and makes the prompt less focused.

This module shows how a **decision model as a pre-turn gate** can answer one
small, typed question set about the user prompt and turn that answer into a
concrete context profile:

| Gate answer | Tool profile | Skill profile | MCP profile | Model hint |
|---|---|---|---|---|
| `trivial` | `MINIMAL` (read, grep, glob, skill) | `NONE` | `NONE` | `cheap` |
| `moderate`, no external system | `STANDARD` | `CORE` | `NONE` | `default` |
| `moderate`, external system | `STANDARD` | `CORE` | `SELECTED` | `default` |
| `complex`, no external system | `FULL` | `TOP_K` | `NONE` | `strong` |
| `complex`, external system | `FULL` | `TOP_K` | `ALL` | `strong` |

The gate is **not** part of the agent loop. It runs once per user turn, before
`ContextBuilder.prepare()`, and never calls tools or streams text.

---

## Why LangChain4j `DecisionModel`

`DecisionModel` is a typed alternative to "please answer with JSON":

- one request holds the input and **named questions**;
- each question has a type: `ChoiceQuestion`, `YesNoQuestion`, `ScaleQuestion`;
- one call answers **all** questions against the same input;
- the response is typed (`ChoiceAnswer`, `YesNoAnswer`, `ScaleAnswer`) and is
  validated by `DecisionModel.decide()` itself: a choice must name an offered
  option, a scale answer must stay within the levels;
- refusals are explicit (`response.isRefused(name)`), so a safety refusal is
  not silently treated as "no".

That maps directly onto the gate use case: classify, route, gate, grade.

---

## Files

| File | Purpose |
|---|---|
| `DecisionGate.java` | Minimal gate interface plus a no-op implementation |
| `LangChain4jDecisionGate.java` | Builds the `DecisionRequest`, calls the `DecisionModel`, maps the answer to a `TaskGateDecision` |
| `ScriptedDecisionModel.java` | Offline `DecisionModel` with a keyword heuristic; used by the demo and tests |
| `TaskGateDecision.java` | The gate result: complexity, tool/skill/MCP profile, model hint, confidence, source |
| `TaskComplexity.java` | `TRIVIAL`, `MODERATE`, `COMPLEX` |
| `ToolProfile.java` | `MINIMAL`, `STANDARD`, `FULL` |
| `SkillProfile.java` | `NONE`, `CORE`, `TOP_K`, `ALL` |
| `McpProfile.java` | `NONE`, `SELECTED`, `ALL` |
| `TicketClassifier.java` | Typed System One classifier (`SUPPORT`, `BILLING`, `SALES`, `SPAM`) |
| `TicketCategory.java` | Ticket routing enum |
| `TicketClassification.java` | Category plus confidence and source |
| `DecisionModelSettings.java` | Base URL, API key, model, timeout and retries from env/properties |
| `DecisionModels.java` | TypeSafe/System One `DecisionModel` factory |
| `Demo.java` | Runnable example with four prompts |

---

## Run the demo

No API key and no network access are needed; the demo uses
`ScriptedDecisionModel`:

```bash
cd qoc
mvn -q -pl decision-gate exec:java
```

Expected shape:

```text
prompt: Lies die README und sage mir, wie das Projekt startet
  complexity=TRIVIAL tools=MINIMAL skills=NONE mcp=NONE model=cheap confidence=0.82 source=decision-model:jev-scripted-demo

prompt: Implementiere ein Decision-Gate-Submodul mit Tests und aktualisiere die Doku
  complexity=COMPLEX tools=FULL skills=TOP_K mcp=NONE model=strong confidence=0.82 source=decision-model:jev-scripted-demo
```

Run the tests:

```bash
cd qoc
mvn -q -pl decision-gate test
```

---

## Typed ticket classifier

`TicketClassifier` is the direct equivalent of the usual enum-returning
`@Decide` service sketch, but uses the real `DecisionModel` API:

```java
DecisionModel model = DecisionModels.typeSafeFromEnvironment();
TicketClassifier classifier = new TicketClassifier(model);

TicketCategory category = classifier.classify("My invoice has the wrong amount");
TicketClassification detailed = classifier.classifyDetailed("My invoice has the wrong amount");
```

The request contains one named `ChoiceQuestion` (`support`, `billing`, `sales`,
`spam`). LangChain4j validates that the answer names one of those options.
`classifyDetailed(...)` additionally exposes `confidence` and `source`;
refusals throw an explicit `IllegalStateException` instead of being mapped to a
random category.

---

## Wiring a real decision model

The example module is intentionally independent of the Quarkus stack: QOC pins
LangChain4j through the Quarkus BOM (currently core `1.19.3`), which predates
the experimental decision API. The module opts in to `langchain4j-core:1.22.0`
explicitly.

For a real model, replace `ScriptedDecisionModel` with an implementation of
`DecisionModel`. LangChain4j provides the TypeSafe System One integration
(`jev-latest`) in `dev.langchain4j:langchain4j-typesafe`:

```java
DecisionModel jev = DecisionModels.typeSafe(new DecisionModelSettings(
        "https://api.typesafe.ai",
        System.getenv("TYPESAFE_API_KEY"),
        "jev-latest",
        Duration.ofSeconds(10),
        2));

DecisionGate gate = new LangChain4jDecisionGate(jev);
TaskGateDecision decision = gate.decide("Implement the new A2A endpoint");
```

Any server that implements the same System One API (including self-hosted open
models) can be used through `baseUrl(...)`.

### Configuration

`DecisionModelSettings` reads the same values from three sources:

```properties
# decision-gate/src/main/resources/application.properties.example
qoc.decision.base-url=https://api.typesafe.ai
qoc.decision.api-key=${TYPESAFE_API_KEY}
qoc.decision.model=jev-latest
qoc.decision.timeout=10s
qoc.decision.max-retries=2
```

- `DecisionModelSettings.fromEnvironment()` reads `TYPESAFE_*` and
  `QOC_DECISION_*`.
- `DecisionModelSettings.fromSystemProperties()` reads `qoc.decision.*`.
- `DecisionModelSettings.fromApplicationProperties(Path)` loads a properties
  file and resolves `${ENV_NAME}` placeholders.

The API key is redacted from `toString()`. A missing key with the default
TypeSafe endpoint fails at model construction; custom System One endpoints may
run without a key.

### Quarkus wiring

The Quarkus LangChain4j **chat** extensions (`quarkus-langchain4j-ollama`,
`quarkus-langchain4j-openai`) are not the System One decision API and do not
provide `@Decide`/`@RegisterAiService` for `DecisionModel`. Keep the decision
model behind a CDI producer and inject it as a normal bean:

```java
@ApplicationScoped
public class DecisionModelProducer {

    @ConfigProperty(name = "qoc.decision.base-url", defaultValue = DecisionModelSettings.DEFAULT_BASE_URL)
    String baseUrl;

    @ConfigProperty(name = "qoc.decision.api-key", defaultValue = "")
    String apiKey;

    @ConfigProperty(name = "qoc.decision.model", defaultValue = DecisionModelSettings.DEFAULT_MODEL)
    String model;

    @Produces
    @ApplicationScoped
    DecisionModel decisionModel() {
        return DecisionModels.typeSafe(new DecisionModelSettings(
                baseUrl, apiKey, model, Duration.ofSeconds(10), 2));
    }
}
```

This keeps URL, key and model in `application.properties` without coupling the
gate to a chat model. The producer is a template for a Quarkus application; this
module itself stays plain Java so it can use the newer experimental
`DecisionModel` API independently of QOC's pinned Quarkus LangChain4j version.


---

## How the result would be applied in QOC

In `Engine.prompt()`, before `ContextBuilder.prepare()`:

```java
TaskGateDecision decision = decisionGate.decide(userPrompt);

ToolSnapshot tools = switch (decision.tools()) {
    case MINIMAL -> snapshot.filter(Set.of("read", "grep", "glob", "skill"));
    case STANDARD, FULL -> agentToolSnapshot;
};

SkillFilterOptions skillOptions = switch (decision.skills()) {
    case NONE -> SkillFilterOptions.none();
    case CORE -> SkillFilterOptions.core();
    case TOP_K -> SkillFilterOptions.semantic(userPrompt, 12);
    case ALL -> SkillFilterOptions.all();
};

var prepared = contextBuilder.prepare(info, agent, model, tools,
        Seq.zero(), stepNo, forcedChoice, userPrompt, skillOptions);
```

`ContextBuilder` and `SkillCatalog` keep their current permission checks: the
gate only narrows what is already allowed.

---

## Design rules

1. **Never block the turn.** Exceptions, refusals and unparsable answers return
   `TaskGateDecision.fallback(...)`, which preserves the current behaviour.
2. **Never widen permissions.** The gate only removes tools/skills/MCP; the
   existing `PermissionRules` and `ToolSnapshot` stay authoritative.
3. **Keep the core tools.** Even `MINIMAL` includes `read`, `grep`, `glob` and
   `skill`, so the model can still discover what it needs.
4. **Log the decision.** `TaskGateDecision.summary()` is meant for the session
   journal, so routing can be audited and tuned later.
5. **Threshold by catalogue size.** Below roughly 15–20 skills/tools the gate
   adds latency without saving much; keep `DecisionGate.noop()` there.

---

## Relationship to `docs/context-build.md`

`context-build.md` describes the strategies (full list, phase-based, semantic,
hybrid, on-demand tool). This module is the smallest runnable example of the
**hybrid** strategy with the decision model acting as the **gate** in front of
it.
