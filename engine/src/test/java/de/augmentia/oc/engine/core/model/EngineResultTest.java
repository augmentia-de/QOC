package de.augmentia.oc.engine.core.model;

import de.augmentia.oc.engine.core.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EngineResultTest {

    @Test
    void completedDefaults() {
        EngineResult r = EngineResult.completed("hello", "COMPLETED", 10, 20);
        assertThat(r.text()).isEqualTo("hello");
        assertThat(r.finish()).isEqualTo("COMPLETED");
        assertThat(r.inputTokens()).isEqualTo(10);
        assertThat(r.outputTokens()).isEqualTo(20);
        assertThat(r.failed()).isFalse();
        assertThat(r.paused()).isFalse();
        assertThat(r.retryable()).isFalse();
        assertThat(r.errorMessage()).isNull();
        assertThat(r.approvalId()).isNull();
        assertThat(r.structured().isEmpty()).isTrue();
    }

    @Test
    void completedWithStructured() {
        Option<JsonValue> structured = Option.some(
                new JsonValue.Obj(Map.of("answer", new JsonValue.Str("42"))));
        EngineResult r = EngineResult.completed("text", "COMPLETED", 1, 2, structured);
        assertThat(r.structured().isPresent()).isTrue();
        assertThat(r.structured().orElseThrow()).isInstanceOf(JsonValue.Obj.class);
    }

    @Test
    void completedWithNullStructured() {
        EngineResult r = EngineResult.completed("text", "COMPLETED", 1, 2, null);
        assertThat(r.structured().isEmpty()).isTrue();
    }

    @Test
    void failed() {
        EngineResult r = EngineResult.failed("boom", true);
        assertThat(r.failed()).isTrue();
        assertThat(r.errorMessage()).isEqualTo("boom");
        assertThat(r.retryable()).isTrue();
        assertThat(r.finish()).isEqualTo("FAILED");
        assertThat(r.text()).isEmpty();
    }

    @Test
    void cancelled() {
        EngineResult r = EngineResult.cancelled();
        assertThat(r.finish()).isEqualTo("CANCELLED");
        assertThat(r.failed()).isFalse();
        assertThat(r.paused()).isFalse();
    }

    @Test
    void paused() {
        EngineResult r = EngineResult.paused("apr-1", "http://url", "msg");
        assertThat(r.paused()).isTrue();
        assertThat(r.approvalId()).isEqualTo("apr-1");
        assertThat(r.text()).isEqualTo("msg");
        assertThat(r.finish()).isEqualTo("PAUSED");
    }

    @Test
    void busy() {
        EngineResult r = EngineResult.busy("busy msg");
        assertThat(r.finish()).isEqualTo("BUSY");
        assertThat(r.text()).isEmpty();
    }

    @Test
    void nullTextAndFinishNormalized() {
        EngineResult r = new EngineResult(null, null, 0, 0, false, null, false, false, null, null);
        assertThat(r.text()).isEqualTo("");
        assertThat(r.finish()).isEqualTo("COMPLETED");
        assertThat(r.structured().isEmpty()).isTrue();
    }
}
