package de.augmentia.oc.engine.runtime.tool.a2a;

import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2AAgentCardCacheTest {

    @Test
    void constructorRejectsNegativeTtl() {
        assertThatThrownBy(() -> new A2AAgentCardCache(Duration.ofSeconds(-1), Clock.systemUTC(), (u, h) -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsNulls() {
        assertThatThrownBy(() -> new A2AAgentCardCache(Duration.ZERO, null, (u, h) -> null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new A2AAgentCardCache(Duration.ZERO, Clock.systemUTC(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void zeroTtlDisablesCaching() {
        AtomicInteger calls = new AtomicInteger();
        A2AAgentCardCache cache = new A2AAgentCardCache(Duration.ZERO, Clock.systemUTC(), (u, h) -> {
            calls.incrementAndGet();
            return agentCard();
        });
        cache.resolve("http://x");
        cache.resolve("http://x");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void freshEntryIsCached() {
        AtomicInteger calls = new AtomicInteger();
        A2AAgentCardCache cache = new A2AAgentCardCache(Duration.ofMinutes(5), Clock.systemUTC(), (u, h) -> {
            calls.incrementAndGet();
            return agentCard();
        });
        A2AAgentCardCache.Resolution r1 = cache.resolve("http://x");
        A2AAgentCardCache.Resolution r2 = cache.resolve("http://x");
        assertThat(r1.cached()).isFalse();
        assertThat(r2.cached()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void failureNotCached() {
        AtomicInteger calls = new AtomicInteger();
        A2AAgentCardCache cache = new A2AAgentCardCache(Duration.ofMinutes(5), Clock.systemUTC(), (u, h) -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
        });
        try { cache.resolve("http://x"); } catch (RuntimeException ignored) { }
        assertThat(calls.get()).isEqualTo(1);
        try { cache.resolve("http://x"); } catch (RuntimeException ignored) { }
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void authHeadersArePartOfKey() {
        AtomicInteger calls = new AtomicInteger();
        A2AAgentCardCache cache = new A2AAgentCardCache(Duration.ofMinutes(5), Clock.systemUTC(), (u, h) -> {
            calls.incrementAndGet();
            return agentCard();
        });
        cache.resolve("http://x", Map.of("Authorization", "Bearer a"));
        cache.resolve("http://x", Map.of("Authorization", "Bearer b"));
        assertThat(calls.get()).isEqualTo(2);
    }

    private static AgentCard agentCard() {
        return AgentCard.builder()
                .name("test")
                .description("test")
                .version("1.0")
                .defaultInputModes(java.util.List.of("text/plain"))
                .defaultOutputModes(java.util.List.of("text/plain"))
                .skills(java.util.List.of())
                .supportedInterfaces(java.util.List.of())
                .capabilities(new org.a2aproject.sdk.spec.AgentCapabilities(true, true, true, java.util.List.of()))
                .build();
    }
}
