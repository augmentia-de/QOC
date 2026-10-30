package de.augmentia.qoc.decisiongate;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;

import java.util.Objects;

/**
 * Typed ticket classifier backed by a LangChain4j {@link DecisionModel}.
 *
 * <p>This is the DecisionModel equivalent of the usual
 * {@code @Decide("...") Category classify(String text)} AI-service sketch:
 * the model gets one named choice question and returns a validated
 * {@link ChoiceAnswer}. The caller gets an enum, not free-form JSON.</p>
 */
public final class TicketClassifier {

    public static final String CATEGORY = "category";

    private final DecisionModel model;

    public TicketClassifier(DecisionModel model) {
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    /**
     * Classifies a ticket.
     *
     * @throws IllegalArgumentException for a blank ticket
     * @throws IllegalStateException if the model refuses the category question
     */
    public TicketCategory classify(String text) {
        return classifyDetailed(text).category();
    }

    /**
     * Classifies a ticket and keeps the model's confidence and model name.
     *
     * @throws IllegalArgumentException for a blank ticket
     * @throws IllegalStateException if the model refuses the category question
     */
    public TicketClassification classifyDetailed(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("ticket text must not be blank");
        DecisionResponse response = model.decide(request(text));
        if (response.isRefused(CATEGORY)) {
            throw new IllegalStateException("decision model refused the ticket category");
        }
        ChoiceAnswer answer = response.choice(CATEGORY);
        return new TicketClassification(
                TicketCategory.parse(answer.value()),
                confidence(answer),
                source(response));
    }

    /** The request shape; package-visible so tests can inspect the options. */
    static DecisionRequest request(String text) {
        return DecisionRequest.builder()
                .input(text)
                .question(CATEGORY, ChoiceQuestion.builder()
                        .text("Classify the intent of this customer request into exactly one category.")
                        .option("support", "Product help, troubleshooting, bugs or how-to questions")
                        .option("billing", "Invoices, payments, refunds or subscription charges")
                        .option("sales", "Pricing, upgrades, demos or new purchases")
                        .option("spam", "Unrelated, abusive or automated bulk content")
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
        if (name == null || name.isBlank()) name = model.modelName();
        return "decision-model:" + (name == null || name.isBlank() ? "unknown" : name);
    }
}
