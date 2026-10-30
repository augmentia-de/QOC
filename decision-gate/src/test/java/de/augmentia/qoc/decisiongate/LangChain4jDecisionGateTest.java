package de.augmentia.qoc.decisiongate;

import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.DecisionRequest;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.RefusalAnswer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jDecisionGateTest {

    private static final DecisionGate GATE =
            new LangChain4jDecisionGate(ScriptedDecisionModel.heuristic());

    @Test
    void trivialPromptGetsMinimalContext() {
        TaskGateDecision decision = GATE.decide("Zeige mir die README");

        assertThat(decision.complexity()).isEqualTo(TaskComplexity.TRIVIAL);
        assertThat(decision.tools()).isEqualTo(ToolProfile.MINIMAL);
        assertThat(decision.skills()).isEqualTo(SkillProfile.NONE);
        assertThat(decision.mcp()).isEqualTo(McpProfile.NONE);
        assertThat(decision.modelHint()).isEqualTo("cheap");
        assertThat(decision.confidence()).isBetween(0.0, 1.0);
        assertThat(decision.source()).startsWith("decision-model:");
    }

    @Test
    void complexPromptGetsFullContext() {
        TaskGateDecision decision = GATE.decide(
                "Implementiere das neue Modul inklusive Tests und aktualisiere die Dokumentation");

        assertThat(decision.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(decision.tools()).isEqualTo(ToolProfile.FULL);
        assertThat(decision.skills()).isEqualTo(SkillProfile.TOP_K);
        assertThat(decision.modelHint()).isEqualTo("strong");
    }

    @Test
    void externalSystemEnablesMcp() {
        TaskGateDecision decision = GATE.decide(
                "Implementiere einen GitHub-Webhook und teste ihn gegen die API");

        assertThat(decision.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(decision.mcp()).isEqualTo(McpProfile.ALL);
    }

    @Test
    void moderatePromptStaysStandard() {
        TaskGateDecision decision = GATE.decide(
                "Analysiere die Klasse Engine und beschreibe den Ablauf");

        assertThat(decision.complexity()).isEqualTo(TaskComplexity.MODERATE);
        assertThat(decision.tools()).isEqualTo(ToolProfile.STANDARD);
        assertThat(decision.skills()).isEqualTo(SkillProfile.CORE);
        assertThat(decision.mcp()).isEqualTo(McpProfile.NONE);
    }

    @Test
    void blankPromptFallsBack() {
        TaskGateDecision decision = GATE.decide("   ");

        assertThat(decision.source()).isEqualTo("empty prompt");
        assertThat(decision.complexity()).isEqualTo(TaskComplexity.MODERATE);
        assertThat(decision.confidence()).isZero();
    }

    @Test
    void failingModelFallsBack() {
        DecisionGate gate = new LangChain4jDecisionGate(request -> {
            throw new IllegalStateException("provider down");
        });

        TaskGateDecision decision = gate.decide("Implementiere etwas");

        assertThat(decision.source()).contains("provider down");
        assertThat(decision.complexity()).isEqualTo(TaskComplexity.MODERATE);
        assertThat(decision.tools()).isEqualTo(ToolProfile.STANDARD);
    }

    @Test
    void refusedDecisionFallsBack() {
        DecisionModel refusing = new ScriptedDecisionModel(request -> DecisionResponse.builder()
                .answer(LangChain4jDecisionGate.COMPLEXITY, RefusalAnswer.of())
                .answer(LangChain4jDecisionGate.EXTERNAL_SYSTEM,
                        dev.langchain4j.model.decision.response.YesNoAnswer.of(0.0))
                .answer(LangChain4jDecisionGate.EFFORT,
                        dev.langchain4j.model.decision.response.ScaleAnswer.builder().mean(0.0).build())
                .build());

        TaskGateDecision decision = new LangChain4jDecisionGate(refusing).decide("Irgendetwas");

        assertThat(decision.source()).isEqualTo("complexity refused");
        assertThat(decision.source()).doesNotContain("decision-model");
    }

    @Test
    void requestHasAllThreeQuestionTypes() {
        DecisionRequest request = LangChain4jDecisionGate.request("Test");

        assertThat(request.questions()).containsKeys(
                LangChain4jDecisionGate.COMPLEXITY,
                LangChain4jDecisionGate.EXTERNAL_SYSTEM,
                LangChain4jDecisionGate.EFFORT);
        assertThat(request.questions().get(LangChain4jDecisionGate.COMPLEXITY))
                .isInstanceOf(dev.langchain4j.model.decision.request.ChoiceQuestion.class);
        assertThat(request.questions().get(LangChain4jDecisionGate.EXTERNAL_SYSTEM))
                .isInstanceOf(dev.langchain4j.model.decision.request.YesNoQuestion.class);
        assertThat(request.questions().get(LangChain4jDecisionGate.EFFORT))
                .isInstanceOf(dev.langchain4j.model.decision.request.ScaleQuestion.class);
    }
}
