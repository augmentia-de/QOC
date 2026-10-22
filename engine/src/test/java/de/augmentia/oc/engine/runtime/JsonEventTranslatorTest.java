package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.runtime.provider.client.JsonEventTranslator;
import de.augmentia.oc.engine.runtime.provider.client.SseReader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonEventTranslatorTest {

    private final JsonEventTranslator translator = new JsonEventTranslator();

    @Test
    void translatesTextAndReasoningEvents() {
        assertThat(translate("{\"type\":\"text.started\",\"blockId\":\"b1\"}"))
                .containsExactly(new ModelEvent.TextStarted("b1"));
        assertThat(translate("{\"type\":\"text.delta\",\"blockId\":\"b1\",\"text\":\"hi\"}"))
                .containsExactly(new ModelEvent.TextDelta("b1", "hi"));
        assertThat(translate("{\"type\":\"reasoning.ended\",\"blockId\":\"r1\",\"text\":\"why\"}"))
                .containsExactly(new ModelEvent.ReasoningEnded("r1", "why"));
    }

    @Test
    void translatesToolEvents() {
        String callId = de.augmentia.oc.engine.core.model.CallId.create().value();
        assertThat(translate("{\"type\":\"tool.input.started\",\"callId\":\"" + callId + "\",\"name\":\"read\"}"))
                .containsExactly(new ModelEvent.ToolInputStarted(
                        de.augmentia.oc.engine.core.model.CallId.parse(callId),
                        new de.augmentia.oc.engine.core.model.ToolName("read")));
        assertThat(translate("{\"type\":\"tool.input.delta\",\"callId\":\"" + callId + "\",\"text\":\"{\"}"))
                .containsExactly(new ModelEvent.ToolInputDelta(
                        de.augmentia.oc.engine.core.model.CallId.parse(callId), "{"));
        assertThat(translate("{\"type\":\"tool.call\",\"callId\":\"" + callId + "\",\"name\":\"read\",\"input\":{}}"))
                .hasSize(1);
    }

    @Test
    void translatesFinishedWithUsage() {
        List<ModelEvent> events = translate(
                "{\"type\":\"finished\",\"reason\":\"normal\",\"usage\":{\"input\":3,\"output\":4}}");
        ModelEvent.Finished finished = (ModelEvent.Finished) events.getFirst();
        assertThat(finished.reason()).isEqualTo(FinishReason.NORMAL);
        assertThat(finished.usage().total()).isEqualTo(7);
    }

    @Test
    void mapsAllFinishReasons() {
        assertThat(((ModelEvent.Finished) translate("{\"type\":\"finished\",\"reason\":\"length\"}").getFirst())
                .reason()).isEqualTo(FinishReason.LENGTH_LIMIT);
        assertThat(((ModelEvent.Finished) translate("{\"type\":\"finished\",\"reason\":\"content_filter\"}").getFirst())
                .reason()).isEqualTo(FinishReason.CONTENT_FILTER);
        assertThat(((ModelEvent.Finished) translate("{\"type\":\"finished\",\"reason\":\"weird\"}").getFirst())
                .reason()).isEqualTo(FinishReason.UNKNOWN);
    }

    @Test
    void translatesProviderFailureWithKind() {
        ModelEvent.ProviderFailed failed = (ModelEvent.ProviderFailed) translate(
                "{\"type\":\"provider.failed\",\"kind\":\"rate_limited\",\"message\":\"slow\",\"status\":429,"
                        + "\"retryAfter\":1000}").getFirst();
        assertThat(failed.kind()).isInstanceOf(FailureKind.RateLimited.class);
        assertThat(failed.status().orElse(0)).isEqualTo(429);
        assertThat(failed.retryAfter().orElseThrow().toMillis()).isEqualTo(1000);
    }

    @Test
    void malformedJsonBecomesInvalidOutput() {
        ModelEvent.ProviderFailed failed = (ModelEvent.ProviderFailed) translate("{not json").getFirst();
        assertThat(failed.kind()).isInstanceOf(FailureKind.InvalidOutput.class);
    }

    @Test
    void unknownTypeBecomesInvalidOutput() {
        ModelEvent.ProviderFailed failed = (ModelEvent.ProviderFailed) translate("{\"type\":\"unknown\"}").getFirst();
        assertThat(failed.kind()).isInstanceOf(FailureKind.InvalidOutput.class);
    }

    @Test
    void doneAndEmptyFramesAreIgnored() {
        assertThat(translate("[DONE]")).isEmpty();
        assertThat(translate("")).isEmpty();
    }

    @Test
    void malformedCallIdBecomesInvalidOutput() {
        ModelEvent.ProviderFailed failed = (ModelEvent.ProviderFailed) translate(
                "{\"type\":\"tool.call\",\"callId\":\"nope\",\"name\":\"read\",\"input\":{}}").getFirst();
        assertThat(failed.kind()).isInstanceOf(FailureKind.InvalidOutput.class);
    }

    private List<ModelEvent> translate(String json) {
        return translator.translate(new SseReader.SseFrame("message", json));
    }
}
