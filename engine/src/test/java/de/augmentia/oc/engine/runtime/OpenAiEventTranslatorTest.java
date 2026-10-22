package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.runtime.provider.client.OpenAiEventTranslator;
import de.augmentia.oc.engine.runtime.provider.client.SseReader;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiEventTranslatorTest {

    @Test
    void translatesTextAndUsage() {
        OpenAiEventTranslator translator = new OpenAiEventTranslator();
        List<ModelEvent> events = new ArrayList<>();
        events.addAll(translator.translate(frame("{\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}")));
        events.addAll(translator.translate(frame("{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}")));
        events.addAll(translator.translate(frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")));
        events.addAll(translator.translate(frame("{\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}")));
        events.addAll(translator.translate(frame("[DONE]")));

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .containsExactly("TextStarted", "TextDelta", "TextDelta", "TextEnded", "Finished");
        ModelEvent.Finished finished = (ModelEvent.Finished) events.getLast();
        assertThat(finished.reason()).isEqualTo(FinishReason.NORMAL);
        assertThat(finished.usage().total()).isEqualTo(7);
    }

    @Test
    void translatesToolCallsSplitAcrossChunks() {
        OpenAiEventTranslator translator = new OpenAiEventTranslator();
        List<ModelEvent> events = new ArrayList<>();
        events.addAll(translator.translate(frame(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\"}}]}}]}")));
        events.addAll(translator.translate(frame(
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]}}]}")));
        events.addAll(translator.translate(frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
        events.addAll(translator.translate(frame("[DONE]")));

        assertThat(events).extracting(event -> event.getClass().getSimpleName())
                .contains("ToolInputStarted", "ToolInputDelta", "ToolInputEnded", "ToolCallRequested");
        ModelEvent.ToolCallRequested call = (ModelEvent.ToolCallRequested) events.stream()
                .filter(ModelEvent.ToolCallRequested.class::isInstance).findFirst().orElseThrow();
        assertThat(call.name().value()).isEqualTo("read");
        assertThat(call.input()).isEqualTo(de.augmentia.oc.engine.core.json.Json.parse("{\"path\":\"a.txt\"}")
                .valueOrNull());
    }

    @Test
    void mapsContentFilterAndUnknownFinish() {
        OpenAiEventTranslator filter = new OpenAiEventTranslator();
        filter.translate(frame("{\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}"));
        List<ModelEvent> filterEvents = filter.translate(frame("[DONE]"));
        assertThat(((ModelEvent.Finished) filterEvents.getLast()).reason())
                .isEqualTo(FinishReason.CONTENT_FILTER);

        OpenAiEventTranslator unknown = new OpenAiEventTranslator();
        unknown.translate(frame("{\"choices\":[{\"delta\":{\"content\":\"x\"}}]}"));
        List<ModelEvent> unknownEvents = unknown.translate(frame("[DONE]"));
        assertThat(((ModelEvent.Finished) unknownEvents.getLast()).reason())
                .isEqualTo(FinishReason.UNKNOWN);
    }

    @Test
    void errorChunkBecomesProviderFailure() {
        OpenAiEventTranslator translator = new OpenAiEventTranslator();
        List<ModelEvent> events = translator.translate(frame(
                "{\"error\":{\"code\":\"rate_limit_exceeded\",\"type\":\"rate_limit\",\"message\":\"slow\"}}"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ModelEvent.ProviderFailed.class);
        assertThat(((ModelEvent.ProviderFailed) events.getFirst()).kind())
                .isInstanceOf(FailureKind.RateLimited.class);
    }

    private static SseReader.SseFrame frame(String data) {
        return new SseReader.SseFrame("message", data);
    }
}
