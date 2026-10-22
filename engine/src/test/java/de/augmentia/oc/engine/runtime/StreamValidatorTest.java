package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.provider.client.StreamValidator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamValidatorTest {

    private final StreamValidator validator = new StreamValidator();

    @Test
    void validTextAndReasoningStreamPasses() {
        List<ModelEvent> events = validator.validate(List.of(
                new ModelEvent.TextStarted("t1"),
                new ModelEvent.TextDelta("t1", "hi"),
                new ModelEvent.TextEnded("t1", "hi"),
                new ModelEvent.ReasoningStarted("r1"),
                new ModelEvent.ReasoningEnded("r1", "why"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.of(1, 2), JsonValue.ofNull())));

        assertThat(events).hasSize(6);
        assertThat(validator.terminalSeen()).isTrue();
    }

    @Test
    void validToolStreamPasses() {
        CallId call = CallId.create();
        List<ModelEvent> events = validator.validate(List.of(
                new ModelEvent.ToolInputStarted(call, new ToolName("read")),
                new ModelEvent.ToolInputDelta(call, "{}"),
                new ModelEvent.ToolInputEnded(call, "{}"),
                new ModelEvent.ToolCallRequested(call, new ToolName("read"), JsonValue.ofNull()),
                new ModelEvent.ToolResultProvided(call, new OutcomePayload.Text("ok"), false),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull())));

        assertThat(events).hasSize(6);
    }

    @Test
    void deltaWithoutBlockStartIsADefect() {
        assertThatThrownBy(() -> validator.validate(List.of(new ModelEvent.TextDelta("t1", "x"))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void blockStartedTwiceIsADefect() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new ModelEvent.TextStarted("t1"), new ModelEvent.TextStarted("t1"))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void blockEndedTwiceIsADefect() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new ModelEvent.TextStarted("t1"), new ModelEvent.TextEnded("t1", "x"),
                new ModelEvent.TextEnded("t1", "x"))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void resultBeforeCallIsADefect() {
        CallId call = CallId.create();
        assertThatThrownBy(() -> validator.validate(List.of(
                new ModelEvent.ToolResultProvided(call, new OutcomePayload.Text("ok"), false))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void doubleResultIsADefect() {
        CallId call = CallId.create();
        assertThatThrownBy(() -> validator.validate(List.of(
                new ModelEvent.ToolCallRequested(call, new ToolName("read"), JsonValue.ofNull()),
                new ModelEvent.ToolResultProvided(call, new OutcomePayload.Text("ok"), false),
                new ModelEvent.ToolResultProvided(call, new OutcomePayload.Text("again"), false))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void changedToolNameIsADefect() {
        CallId call = CallId.create();
        assertThatThrownBy(() -> validator.validate(List.of(
                new ModelEvent.ToolInputStarted(call, new ToolName("read")),
                new ModelEvent.ToolCallRequested(call, new ToolName("write"), JsonValue.ofNull()))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void eventAfterTerminalIsADefect() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()),
                new ModelEvent.TextStarted("t1"))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void unfinishedBlockAtTerminalIsADefect() {
        assertThatThrownBy(() -> validator.validate(List.of(
                new ModelEvent.TextStarted("t1"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))))
                .isInstanceOf(Defect.class);
    }

    @Test
    void contentFilterBecomesContentPolicyFailure() {
        List<ModelEvent> events = validator.validate(List.of(
                new ModelEvent.Finished(FinishReason.CONTENT_FILTER, TokenCount.zero(), JsonValue.ofNull())));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ModelEvent.ProviderFailed.class);
        assertThat(((ModelEvent.ProviderFailed) events.getFirst()).kind())
                .isInstanceOf(FailureKind.ContentPolicy.class);
    }

    @Test
    void unknownFinishBecomesIncompleteStreamFailure() {
        List<ModelEvent> events = validator.validate(List.of(
                new ModelEvent.Finished(FinishReason.UNKNOWN, TokenCount.zero(), JsonValue.ofNull())));

        assertThat(((ModelEvent.ProviderFailed) events.getFirst()).kind())
                .isInstanceOf(FailureKind.InvalidOutput.class);
    }

    @Test
    void finishAddsIncompleteStreamWhenNoTerminalWasSeen() {
        List<ModelEvent> validated = validator.validate(List.of(new ModelEvent.TextStarted("t1")));
        List<ModelEvent> finished = validator.finish(validated);

        assertThat(finished).hasSize(2);
        assertThat(finished.getLast()).isInstanceOf(ModelEvent.ProviderFailed.class);
        assertThat(((ModelEvent.ProviderFailed) finished.getLast()).kind())
                .isInstanceOf(FailureKind.InvalidOutput.class);
    }

    @Test
    void finishDoesNotDoubleAddAfterTerminal() {
        List<ModelEvent> validated = validator.validate(List.of(
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull())));
        assertThat(validator.finish(validated)).hasSize(1);
    }
}
