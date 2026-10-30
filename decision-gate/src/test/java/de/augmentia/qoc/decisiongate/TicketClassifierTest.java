package de.augmentia.qoc.decisiongate;

import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.RefusalAnswer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketClassifierTest {

    @Test
    void classifiesIntoAnEnumAndKeepsConfidence() {
        TicketClassifier classifier = classifier(ChoiceAnswer.builder()
                .value("billing")
                .confidence(0.91)
                .probability("billing", 0.91)
                .build());

        TicketClassification result = classifier.classifyDetailed("My invoice has the wrong amount");

        assertThat(result.category()).isEqualTo(TicketCategory.BILLING);
        assertThat(result.confidence()).isEqualTo(0.91);
        assertThat(result.source()).isEqualTo("decision-model:ticket-test-model");
        assertThat(classifier.classify("I need a refund")).isEqualTo(TicketCategory.BILLING);
    }

    @Test
    void requestOffersAllCategories() {
        var question = (dev.langchain4j.model.decision.request.ChoiceQuestion)
                TicketClassifier.request("Hello").questions().get(TicketClassifier.CATEGORY);

        assertThat(question.options()).containsKeys("support", "billing", "sales", "spam");
    }

    @Test
    void blankTicketIsRejectedBeforeCallingTheModel() {
        TicketClassifier classifier = classifier(ChoiceAnswer.builder().value("support").build());

        assertThatThrownBy(() -> classifier.classify("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticket text");
    }

    @Test
    void refusalIsExplicit() {
        TicketClassifier classifier = new TicketClassifier(new ScriptedDecisionModel(request ->
                DecisionResponse.builder()
                        .modelName("ticket-test-model")
                        .answer(TicketClassifier.CATEGORY, RefusalAnswer.of())
                        .build()));

        assertThatThrownBy(() -> classifier.classify("Anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
    }

    private static TicketClassifier classifier(ChoiceAnswer answer) {
        return new TicketClassifier(new ScriptedDecisionModel(request -> DecisionResponse.builder()
                .modelName("ticket-test-model")
                .answer(TicketClassifier.CATEGORY, answer)
                .build()));
    }
}
