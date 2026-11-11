package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamValidatorTest {

    @Test
    void nullEventsThrows() {
        assertThatThrownBy(() -> new StreamValidator().validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textBlockValid() {
        StreamValidator v = new StreamValidator();
        List<ModelEvent> events = List.of(
                new ModelEvent.TextStarted("b1"),
                new ModelEvent.TextDelta("b1", "hi"),
                new ModelEvent.TextEnded("b1", "hi"),
                new ModelEvent.Finished(FinishReason.NORMAL, new de.augmentia.oc.engine.core.model.TokenCount(1,1), de.augmentia.oc.engine.core.json.JsonValue.ofNull()));
        List<ModelEvent> out = v.validate(events);
        assertThat(out).hasSize(4);
        assertThat(v.terminalSeen()).isTrue();
    }

    @Test
    void eventAfterTerminalThrows() {
        StreamValidator v = new StreamValidator();
        v.validate(List.of(new ModelEvent.Finished(FinishReason.NORMAL, new de.augmentia.oc.engine.core.model.TokenCount(1,1), de.augmentia.oc.engine.core.json.JsonValue.ofNull())));
        assertThatThrownBy(() -> v.validate(List.of(new ModelEvent.TextStarted("b"))))
                .isInstanceOf(de.augmentia.oc.engine.core.error.Defect.class);
    }

    @Test
    void missingTerminalFinishAddsFailure() {
        StreamValidator v = new StreamValidator();
        List<ModelEvent> validated = v.validate(List.of(new ModelEvent.TextStarted("b"), new ModelEvent.TextEnded("b", "hi")));
        List<ModelEvent> finished = v.finish(validated);
        assertThat(finished).hasSize(3);
        assertThat(finished.get(2)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }

    @Test
    void toolCallFlow() {
        StreamValidator v = new StreamValidator();
        CallId id = CallId.create();
        List<ModelEvent> events = List.of(
                new ModelEvent.ToolInputStarted(id, new ToolName("t")),
                new ModelEvent.ToolInputDelta(id, "{\"a\":1}"),
                new ModelEvent.ToolInputEnded(id, "{\"a\":1}"),
                new ModelEvent.ToolCallRequested(id, new ToolName("t"), de.augmentia.oc.engine.core.json.JsonValue.ofNull()),
                new ModelEvent.ToolResultProvided(id, new de.augmentia.oc.engine.core.contract.provider.OutcomePayload.Json(de.augmentia.oc.engine.core.json.JsonValue.ofNull()), false),
                new ModelEvent.Finished(FinishReason.NORMAL, new de.augmentia.oc.engine.core.model.TokenCount(1,1), de.augmentia.oc.engine.core.json.JsonValue.ofNull()));
        List<ModelEvent> out = v.validate(events);
        assertThat(out).hasSize(6);
    }

    @Test
    void toolInputTwiceThrows() {
        StreamValidator v = new StreamValidator();
        CallId id = CallId.create();
        assertThatThrownBy(() -> v.validate(List.of(
                new ModelEvent.ToolInputStarted(id, new ToolName("t")),
                new ModelEvent.ToolInputStarted(id, new ToolName("t")))))
                .isInstanceOf(de.augmentia.oc.engine.core.error.Defect.class);
    }

    @Test
    void toolCallTwiceThrows() {
        StreamValidator v = new StreamValidator();
        CallId id = CallId.create();
        List<ModelEvent> events = List.of(
                new ModelEvent.ToolCallRequested(id, new ToolName("t"), de.augmentia.oc.engine.core.json.JsonValue.ofNull()),
                new ModelEvent.ToolCallRequested(id, new ToolName("t"), de.augmentia.oc.engine.core.json.JsonValue.ofNull()));
        assertThatThrownBy(() -> v.validate(events))
                .isInstanceOf(de.augmentia.oc.engine.core.error.Defect.class);
    }

    @Test
    void contentFilterNormalizesToProviderFailed() {
        StreamValidator v = new StreamValidator();
        List<ModelEvent> out = v.validate(List.of(
                new ModelEvent.Finished(FinishReason.CONTENT_FILTER, new de.augmentia.oc.engine.core.model.TokenCount(1,1), de.augmentia.oc.engine.core.json.JsonValue.ofNull())));
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }

    @Test
    void unknownFinishNormalizesToProviderFailed() {
        StreamValidator v = new StreamValidator();
        List<ModelEvent> out = v.validate(List.of(
                new ModelEvent.Finished(FinishReason.UNKNOWN, new de.augmentia.oc.engine.core.model.TokenCount(1,1), de.augmentia.oc.engine.core.json.JsonValue.ofNull())));
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }

    @Test
    void finishWhenAlreadyTerminal() {
        StreamValidator v = new StreamValidator();
        v.validate(List.of(new ModelEvent.Finished(FinishReason.NORMAL, new de.augmentia.oc.engine.core.model.TokenCount(1,1), de.augmentia.oc.engine.core.json.JsonValue.ofNull())));
        List<ModelEvent> out = v.finish(List.of());
        assertThat(out).isEmpty();
    }
}
