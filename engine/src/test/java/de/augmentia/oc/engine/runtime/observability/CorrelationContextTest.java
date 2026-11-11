package de.augmentia.oc.engine.runtime.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationContextTest {

    @Test
    void setAndGet() {
        CorrelationContext.clear();
        CorrelationContext.set("rid-1", "sid-1");
        assertThat(CorrelationContext.requestId()).isEqualTo("rid-1");
        assertThat(CorrelationContext.sessionId()).isEqualTo("sid-1");
        assertThat(CorrelationContext.format()).isEqualTo("rid=rid-1 sid=sid-1");
    }

    @Test
    void blankValuesIgnored() {
        CorrelationContext.clear();
        CorrelationContext.set("", "sid-2");
        assertThat(CorrelationContext.requestId()).isEmpty();
        assertThat(CorrelationContext.sessionId()).isEqualTo("sid-2");
    }

    @Test
    void nullValuesIgnored() {
        CorrelationContext.clear();
        CorrelationContext.set(null, null);
        assertThat(CorrelationContext.requestId()).isEmpty();
        assertThat(CorrelationContext.sessionId()).isEmpty();
        assertThat(CorrelationContext.format()).isEmpty();
    }

    @Test
    void clearRemoves() {
        CorrelationContext.set("r", "s");
        CorrelationContext.clear();
        assertThat(CorrelationContext.requestId()).isEmpty();
        assertThat(CorrelationContext.sessionId()).isEmpty();
    }

    @Test
    void formatPartial() {
        CorrelationContext.clear();
        CorrelationContext.set("r", "");
        assertThat(CorrelationContext.format()).isEqualTo("rid=r sid=");
    }
}
