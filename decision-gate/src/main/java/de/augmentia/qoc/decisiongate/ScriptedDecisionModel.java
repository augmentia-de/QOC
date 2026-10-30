package de.augmentia.qoc.decisiongate;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.Question;
import dev.langchain4j.model.decision.request.ScaleQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.ScaleAnswer;
import dev.langchain4j.model.decision.response.YesNoAnswer;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Offline {@link DecisionModel} used by the demo and the tests.
 *
 * <p>It answers the gate's questions with a small keyword heuristic instead of
 * calling a remote service. That keeps the example runnable without an API key
 * while exercising the real DecisionModel contract: typed questions in, one
 * typed answer per question out, automatic response validation.</p>
 *
 * <p>Production wiring replaces this class with a real decision model, for
 * example TypeSafe's {@code jev-latest} via
 * {@code dev.langchain4j:langchain4j-typesafe}.</p>
 */
public final class ScriptedDecisionModel implements DecisionModel {

    public static final String MODEL_NAME = "jev-scripted-demo";

    private final Function<DecisionRequest, DecisionResponse> script;
    private final String modelName;

    public ScriptedDecisionModel(Function<DecisionRequest, DecisionResponse> script) {
        this(script, MODEL_NAME);
    }

    public ScriptedDecisionModel(Function<DecisionRequest, DecisionResponse> script, String modelName) {
        this.script = Objects.requireNonNull(script, "script must not be null");
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
        this.modelName = modelName;
    }

    /** A deterministic keyword-based model for the demo. */
    public static ScriptedDecisionModel heuristic() {
        return new ScriptedDecisionModel(ScriptedDecisionModel::heuristicAnswer);
    }

    @Override
    public DecisionResponse doDecide(DecisionRequest request) {
        return script.apply(request);
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.OTHER;
    }

    private static DecisionResponse heuristicAnswer(DecisionRequest request) {
        String input = String.valueOf(request.input());
        TaskComplexity complexity = complexityOf(input);
        boolean external = needsExternalSystem(input);
        int effort = effortOf(complexity);

        DecisionResponse.Builder response = DecisionResponse.builder().modelName(MODEL_NAME);
        for (Map.Entry<String, Question> entry : request.questions().entrySet()) {
            response.answer(entry.getKey(), answer(entry.getValue(), complexity, external, effort));
        }
        return response.build();
    }

    private static DecisionAnswer answer(
            Question question, TaskComplexity complexity, boolean external, int effort) {
        if (question instanceof ChoiceQuestion choice) {
            String value = choice.options().containsKey(complexity.name().toLowerCase(Locale.ROOT))
                    ? complexity.name().toLowerCase(Locale.ROOT)
                    : choice.options().keySet().iterator().next();
            return ChoiceAnswer.builder()
                    .value(value)
                    .confidence(0.82)
                    .probability(value, 0.82)
                    .build();
        }
        if (question instanceof YesNoQuestion) {
            return YesNoAnswer.of(external ? 0.88 : 0.12);
        }
        ScaleQuestion scale = (ScaleQuestion) question;
        int mean = Math.min(effort, scale.levels().size() - 1);
        return ScaleAnswer.builder().mean((double) mean).build();
    }

    static TaskComplexity complexityOf(String raw) {
        String text = raw.toLowerCase(Locale.ROOT);
        int score = 0;
        if (text.length() > 240) score += 1;
        if (containsAny(text, "implement", "implementiere", "refactor", "rewrite", "migrate",
                "architect", "debug", "review", "test suite", "endpoint", "submodule", "modul")) {
            score += 2;
        }
        if (containsAny(text, "analyze", "analyse", "analysiere", "compare", "vergleiche",
                "plan", "design", "document", "dokumentiere")) {
            score += 1;
        }
        if (containsAny(text, "show me", "zeige", "read ", "lies ", "what is", "was ist",
                "where is", "wo ist", "find ", "suche", "typo", "rename variable")) {
            score -= 2;
        }
        if (text.length() < 80 && score == 0) score -= 1;
        if (score <= -2) return TaskComplexity.TRIVIAL;
        if (score >= 2) return TaskComplexity.COMPLEX;
        return TaskComplexity.MODERATE;
    }

    static boolean needsExternalSystem(String raw) {
        String text = raw.toLowerCase(Locale.ROOT);
        return containsAny(text, "github", "gitlab", "jira", "database", "datenbank",
                "deploy", "deployment", "http", "api", "mcp", "webhook", "issue", "slack");
    }

    private static int effortOf(TaskComplexity complexity) {
        return switch (complexity) {
            case TRIVIAL -> 0;
            case MODERATE -> 1;
            case COMPLEX -> 3;
        };
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }
}
