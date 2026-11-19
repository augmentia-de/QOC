package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EngineSubagentOutcomeTest {

    @Test
    void completedOutcomeIsAccepted() {
        Engine.PromptResult result = new Engine.PromptResult(
                new Engine.TurnOutcome.Completed("stop", 1, 2, "done", Option.none()),
                "stop", 1, 2, "done", Option.none());

        assertThatCode(() -> Engine.requireSubagentCompleted("research", result))
                .doesNotThrowAnyException();
    }

    @Test
    void failedOutcomeIsRejected() {
        Engine.PromptResult result = new Engine.PromptResult(
                new Engine.TurnOutcome.Failed(new SessionError.Unknown("child", "boom"), true),
                "FAILED", 0, 0, "boom", Option.none());

        assertThatThrownBy(() -> Engine.requireSubagentCompleted("research", result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not complete")
                .hasMessageContaining("boom");
    }

    @Test
    void cancelledOutcomeIsRejected() {
        Engine.PromptResult result = new Engine.PromptResult(
                new Engine.TurnOutcome.Cancelled(), "CANCELLED", 0, 0, "", Option.none());

        assertThatThrownBy(() -> Engine.requireSubagentCompleted("research", result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED");
    }
}
