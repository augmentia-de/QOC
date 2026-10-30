package de.augmentia.qoc.decisiongate;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.request.ScaleQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;

import java.util.Objects;

/**
 * Example gate backed by a LangChain4j {@link DecisionModel}.
 *
 * <p>One call answers three typed questions about the same prompt:</p>
 * <ul>
 *   <li>{@code complexity} – a choice between trivial, moderate and complex,</li>
 *   <li>{@code externalSystem} – a yes/no question about MCP or network needs,</li>
 *   <li>{@code effort} – a scale question used for logging and calibration.</li>
 * </ul>
 *
 * <p>The response is validated by {@link DecisionModel#decide(DecisionRequest)}
 * itself, so a choice answer always names one of the offered options. Refusals
 * and exceptions degrade to the conservative fallback.</p>
 *
 * <pre>{@code
 * DecisionModel jev = TypeSafeDecisionModel.builder()
 *         .apiKey(System.getenv("TYPESAFE_API_KEY"))
 *         .modelName("jev-latest")
 *         .build();
 * DecisionGate gate = new LangChain4jDecisionGate(jev);
 * TaskGateDecision decision = gate.decide("Implement the new A2A endpoint");
 * }</pre>
 */
public final class LangChain4jDecisionGate implements DecisionGate {

    public static final String COMPLEXITY = "complexity";
    public static final String EXTERNAL_SYSTEM = "externalSystem";
    public static final String EFFORT = "effort";

    /** Threshold above which a yes/no answer counts as "yes". */
    public static final double YES_THRESHOLD = 0.5;

    private final DecisionModel model;

    public LangChain4jDecisionGate(DecisionModel model) {
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    @Override
    public TaskGateDecision decide(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return TaskGateDecision.fallback("empty prompt");
        }
        try {
            DecisionResponse response = model.decide(request(userPrompt));
            if (response.isRefused(COMPLEXITY)) {
                return TaskGateDecision.fallback("complexity refused");
            }
            ChoiceAnswer complexityAnswer = response.choice(COMPLEXITY);
            TaskComplexity complexity = TaskComplexity.parse(complexityAnswer.value());
            boolean external = response.yesNo(EXTERNAL_SYSTEM).isYes(YES_THRESHOLD);
            double confidence = confidence(complexityAnswer);
            return TaskGateDecision.forComplexity(complexity, external, confidence, source(response));
        } catch (RuntimeException e) {
            return TaskGateDecision.fallback("decision model failed: " + message(e));
        }
    }

    /** The request shape; package-visible so tests can inspect it. */
    static DecisionRequest request(String userPrompt) {
        return DecisionRequest.builder()
                .input(userPrompt)
                .question(COMPLEXITY, ChoiceQuestion.builder()
                        .text("How complex is the task?")
                        .option("trivial", "A direct answer or a single lookup; no code change")
                        .option("moderate", "A small change or focused analysis in one or two files")
                        .option("complex", "Many files, design decisions, tests, or several tool steps")
                        .build())
                .question(EXTERNAL_SYSTEM, YesNoQuestion.builder()
                        .text("Does the task need an external system such as GitHub, Jira, "
                                + "a database, an MCP server or a deployed service?")
                        .yesWhen("The task reads from or writes to a system outside the workspace")
                        .noWhen("The task can be done with workspace files only")
                        .build())
                .question(EFFORT, ScaleQuestion.builder()
                        .text("How much effort does the task need?")
                        .level("none", "Answer from the existing context")
                        .level("small", "One file or one lookup")
                        .level("medium", "A few files and one verification step")
                        .level("large", "Many files, tests and iterations")
                        .build())
                .build();
    }

    private static double confidence(ChoiceAnswer answer) {
        Double reported = answer.confidence();
        if (reported != null) return clamp(reported);
        if (!answer.probabilities().isEmpty()) {
            return clamp(answer.probabilities().getOrDefault(answer.value(), 0.0));
        }
        return 0.0;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String source(DecisionResponse response) {
        String name = response.modelName();
        if (name == null || name.isBlank()) {
            name = model.modelName();
        }
        return "decision-model:" + (name == null || name.isBlank() ? "unknown" : name);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
