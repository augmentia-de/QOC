package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.json.JsonValue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonEventTranslatorTest {

    private final JsonEventTranslator t = new JsonEventTranslator();

    @Test
    void nullFrameThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> t.translate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDataReturnsEmpty() {
        assertThat(t.translate(new SseReader.SseFrame("message", ""))).isEmpty();
        assertThat(t.translate(new SseReader.SseFrame("message", "   "))).isEmpty();
    }

    @Test
    void doneReturnsEmpty() {
        assertThat(t.translate(new SseReader.SseFrame("message", "[DONE]"))).isEmpty();
    }

    @Test
    void malformedJsonReturnsFailure() {
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "{bad"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }

    @Test
    void noTypeReturnsFailure() {
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "{\"x\":1}"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }

    @Test
    void textStarted() {
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "{\"type\":\"text.started\",\"blockId\":\"b1\"}"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.TextStarted.class);
    }

    @Test
    void textDelta() {
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "{\"type\":\"text.delta\",\"blockId\":\"b1\",\"text\":\"hi\"}"));
        assertThat(out.get(0)).isInstanceOf(ModelEvent.TextDelta.class);
    }

    @Test
    void textEnded() {
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "{\"type\":\"text.ended\",\"blockId\":\"b1\",\"text\":\"hi\"}"));
        assertThat(out.get(0)).isInstanceOf(ModelEvent.TextEnded.class);
    }

    @Test
    void unknownTypeReturnsFailure() {
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "{\"type\":\"nope\"}"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }

    @Test
    void finished() {
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "{\"type\":\"finished\",\"reason\":\"NORMAL\",\"usage\":{\"input\":1,\"output\":2}}"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.Finished.class);
    }

    @Test
    void providerFailed() {
        List<ModelEvent> out = t.translate(new SseReader.SseFrame("message", "{\"type\":\"provider.failed\",\"message\":\"boom\",\"status\":500}"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).isInstanceOf(ModelEvent.ProviderFailed.class);
    }
}
