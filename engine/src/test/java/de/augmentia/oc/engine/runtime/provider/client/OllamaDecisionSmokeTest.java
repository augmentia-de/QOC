package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.model.DecisionRequest;
import de.augmentia.oc.engine.core.model.DecisionResult;
import de.augmentia.oc.engine.core.model.PromptRelation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test against a real decision backend (local Ollama/tev1 by
 * default). Run with:
 *
 * <pre>
 * ENGINE_DECISION_ENABLED=true \
 * ENGINE_DECISION_BASE_URL=http://127.0.0.1:11434 \
 * ENGINE_DECISION_MODEL=tev1:4b \
 * mvn -pl engine test -Dtest=OllamaDecisionSmokeTest
 * </pre>
 *
 * <p>Skipped unless {@code ENGINE_DECISION_ENABLED=true} is set, so CI does not
 * require a running model.</p>
 */
@EnabledIfEnvironmentVariable(named = "ENGINE_DECISION_ENABLED", matches = "true")
class OllamaDecisionSmokeTest {

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final TypeSafeDecisionAdapter adapter = new TypeSafeDecisionAdapter(
            env("ENGINE_DECISION_BASE_URL", "http://127.0.0.1:11434"),
            env("ENGINE_DECISION_MODEL", "tev1:4b"),
            env("ENGINE_DECISION_API_KEY", null),
            Integer.parseInt(env("ENGINE_DECISION_TIMEOUT_SECONDS", "60")),
            Integer.parseInt(env("ENGINE_DECISION_MAX_RETRIES", "0")));

    @Test
    void promptRelationChoice() {
        DecisionResult result = adapter.decide(new DecisionRequest(
                "prompt_relation",
                "Classify the relation of the new prompt to the running task.",
                List.of(),
                "Add a test for the parser.",
                Map.of("type", "choice", "options", Map.of(
                        "SUPPLEMENT", "The new prompt adds to the running task.",
                        "CORRECTION", "The new prompt corrects the running task.",
                        "INDEPENDENT", "The new prompt is independent and should be queued.",
                        "CANCEL", "The new prompt cancels the running task.",
                        "STATUS", "The new prompt asks for a status update."))));

        System.out.println("prompt_relation -> " + result);
        assertThat(PromptRelation.valueOf(result.answer())).isNotNull();
        assertThat(result.confidence()).isGreaterThan(0.5);
        assertThat(result.noul()).isFalse();
    }

    @Test
    void answerableYesNo() {
        DecisionResult result = adapter.decide(new DecisionRequest(
                "answerable",
                "Is the question answerable from the given context? Question: What is the capital of France?",
                List.of(),
                "Context: The capital of France is Paris.",
                Map.of()));

        System.out.println("answerable -> " + result);
        assertThat(result.answer()).isEqualTo("yes");
        assertThat(result.confidence()).isGreaterThan(0.5);
        assertThat(result.noul()).isFalse();
    }

    @Test
    void answerableNo() {
        DecisionResult result = adapter.decide(new DecisionRequest(
                "answerable",
                "Is the question answerable from the given context? Question: What is the population of Tokyo?",
                List.of(),
                "Context: The capital of France is Paris.",
                Map.of()));

        System.out.println("answerable(no) -> " + result);
        assertThat(result.answer()).isEqualTo("no");
        assertThat(result.confidence()).isGreaterThan(0.5);
        assertThat(result.noul()).isFalse();
    }

    @Test
    void permissionRiskYesNo() {
        DecisionResult result = adapter.decide(new DecisionRequest(
                "permission_risk",
                "Is this operation high risk?",
                List.of(),
                "Tool: shell. Command: rm -rf / --no-preserve-root. "
                        + "This permanently deletes the entire filesystem.",
                Map.of()));

        System.out.println("permission_risk -> " + result);
        assertThat(result.answer()).isEqualTo("yes");
        assertThat(result.confidence()).isGreaterThan(0.5);
        assertThat(result.noul()).isFalse();
    }

    @Test
    void triageYesNo() {
        DecisionResult result = adapter.decide(new DecisionRequest(
                "triage",
                "Is this error retryable with a corrected input?",
                List.of(),
                "Tool read_file failed: file not found: src/Main.java. "
                        + "Input: {\"path\":\"src/Main.java\"}",
                Map.of()));

        System.out.println("triage -> " + result);
        assertThat(result.answer()).isIn("yes", "no");
        assertThat(result.confidence()).isGreaterThan(0.5);
        assertThat(result.noul()).isFalse();
    }
}
