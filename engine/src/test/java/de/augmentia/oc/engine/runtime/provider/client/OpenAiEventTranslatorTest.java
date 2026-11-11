package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.json.JsonValue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiEventTranslatorTest {

    @Test
    void nullFrameThrows() {
        assertThatThrownBy(() -> new OpenAiEventTranslator().translate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDataReturnsEmpty() {
        assertThat(new OpenAiEventTranslator().translate(new SseReader.SseFrame("message", ""))).isEmpty();
        assertThat(new OpenAiEventTranslator().translate(new SseReader.SseFrame("message", "   "))).isEmpty();
    }

    @Test
    void doneEmitsFinished() {
        OpenAiEventTranslator t = new OpenAiEventTranslator();
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "[DONE]"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.Finished.class);
    }

    @Test
    void doneTwiceReturnsEmpty() {
        OpenAiEventTranslator t = new OpenAiEventTranslator();
        t.translate(new SseReader.SseFrame("message", "[DONE]"));
        assertThat(t.translate(new SseReader.SseFrame("message", "[DONE]"))).isEmpty();
    }

    @Test
    void malformedJsonReturnsFailure() {
        List<ModelEvent> out = new OpenAiEventTranslator().translate(new SseReader.SseFrame("message", "{bad"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }

    @Test
    void nonObjectReturnsEmpty() {
        assertThat(new OpenAiEventTranslator().translate(new SseReader.SseFrame("message", "\"x\""))).isEmpty();
    }

    @Test
    void errorObjectReturnsFailure() {
        List<ModelEvent> out = new OpenAiEventTranslator().translate(new SseReader.SseFrame("message",
                "{\"error\":{\"code\":\"rate_limit\",\"type\":\"rate_limit\",\"message\":\"too many\"}}"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }

    @Test
    void textDelta() {
        OpenAiEventTranslator t = new OpenAiEventTranslator();
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message",
                "{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"));
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.TextStarted.class);
        assertThat(out.get(1)).isInstanceOf(ModelEvent.TextDelta.class);
    }

    @Test
    void reasoningDelta() {
        OpenAiEventTranslator t = new OpenAiEventTranslator();
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message",
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}"));
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ReasoningStarted.class);
    }

    @Test
    void finishReasonLength() {
        OpenAiEventTranslator t = new OpenAiEventTranslator();
        t.translate(new SseReader.SseFrame("message", "{\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"length\"}]}"));
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "[DONE]"));
        assertThat(out).anyMatch(e -> e instanceof ModelEvent.Finished);
    }

    @Test
    void usageParsed() {
        OpenAiEventTranslator t = new OpenAiEventTranslator();
        t.translate(new SseReader.SseFrame("message", "{\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3},\"choices\":[{\"delta\":{}}]}"));
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "[DONE]"));
        ModelEvent.Finished f = (ModelEvent.Finished) out.stream().filter(e -> e instanceof ModelEvent.Finished).findFirst().orElseThrow();
        assertThat(f.usage().input()).isEqualTo(5);
        assertThat(f.usage().output()).isEqualTo(3);
    }

    @Test
    void toolCall() {
        OpenAiEventTranslator t = new OpenAiEventTranslator();
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"foo\",\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}"));
        assertThat(out).anyMatch(e -> e instanceof ModelEvent.ToolInputStarted);
    }

    @Test
    void emptyChoicesReturnsEmpty() {
        assertThat(new OpenAiEventTranslator().translate(new SseReader.SseFrame("message", "{\"choices\":[]}"))).isEmpty();
    }
}
