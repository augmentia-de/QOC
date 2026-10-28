package de.augmentia.oc.engine.runtime.tool.a2a;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2AAgentCardCacheTest {
    private static final Instant T0 = Instant.parse("2026-10-28T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

    @Test
    void servesCachedCardWithinTtl() {
        AtomicInteger fetches = new AtomicInteger();
        MutableClock clock = new MutableClock(T0);
        A2AAgentCardCache cache = new A2AAgentCardCache(TTL, clock,
                url -> card(fetches.incrementAndGet()));

        A2AAgentCardCache.Resolution first = cache.resolve("http://agent-a");
        A2AAgentCardCache.Resolution second = cache.resolve("http://agent-a");

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.card()).isSameAs(first.card());
        assertThat(second.fetchedAt()).isEqualTo(T0);
        assertThat(fetches).hasValue(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void refetchesAfterTtlExpired() {
        AtomicInteger fetches = new AtomicInteger();
        MutableClock clock = new MutableClock(T0);
        A2AAgentCardCache cache = new A2AAgentCardCache(TTL, clock,
                url -> card(fetches.incrementAndGet()));

        cache.resolve("http://agent-a");
        clock.advance(TTL.plusSeconds(1));
        A2AAgentCardCache.Resolution second = cache.resolve("http://agent-a");

        assertThat(second.cached()).isFalse();
        assertThat(fetches).hasValue(2);
    }

    @Test
    void ttlOfZeroDisablesCaching() {
        AtomicInteger fetches = new AtomicInteger();
        A2AAgentCardCache cache = new A2AAgentCardCache(Duration.ZERO, new MutableClock(T0),
                url -> card(fetches.incrementAndGet()));

        cache.resolve("http://agent-a");
        A2AAgentCardCache.Resolution second = cache.resolve("http://agent-a");

        assertThat(second.cached()).isFalse();
        assertThat(fetches).hasValue(2);
    }

    @Test
    void invalidationForcesRefetch() {
        AtomicInteger fetches = new AtomicInteger();
        A2AAgentCardCache cache = new A2AAgentCardCache(TTL, new MutableClock(T0),
                url -> card(fetches.incrementAndGet()));

        cache.resolve("http://agent-a");
        cache.resolve("http://agent-b");
        cache.invalidate("http://agent-a");
        cache.resolve("http://agent-a");

        assertThat(fetches).hasValue(3);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void invalidateAllDropsEveryEntry() {
        A2AAgentCardCache cache = new A2AAgentCardCache(TTL, new MutableClock(T0),
                url -> card(1));
        cache.resolve("http://agent-a");
        cache.resolve("http://agent-b");

        cache.invalidate();

        assertThat(cache.size()).isZero();
    }

    @Test
    void failuresAreNotCached() {
        AtomicInteger fetches = new AtomicInteger();
        A2AAgentCardCache cache = new A2AAgentCardCache(TTL, new MutableClock(T0), url -> {
            if (fetches.incrementAndGet() == 1) {
                throw new IllegalStateException("offline");
            }
            return card(2);
        });

        assertThatThrownBy(() -> cache.resolve("http://agent-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("offline");
        A2AAgentCardCache.Resolution recovered = cache.resolve("http://agent-a");

        assertThat(recovered.cached()).isFalse();
        assertThat(fetches).hasValue(2);
    }

    @Test
    void nullCardIsRejectedAndNotCached() {
        A2AAgentCardCache cache = new A2AAgentCardCache(TTL, new MutableClock(T0), url -> null);

        assertThatThrownBy(() -> cache.resolve("http://agent-a"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cache.size()).isZero();
    }

    @Test
    void rejectsBlankUrlAndNegativeTtl() {
        A2AAgentCardCache cache = new A2AAgentCardCache(TTL, new MutableClock(T0), url -> card(1));

        assertThatThrownBy(() -> cache.resolve(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new A2AAgentCardCache(Duration.ofSeconds(-1),
                new MutableClock(T0), url -> card(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsFetchedAtOnlyForFreshEntries() {
        MutableClock clock = new MutableClock(T0);
        A2AAgentCardCache cache = new A2AAgentCardCache(TTL, clock, url -> card(1));

        assertThat(cache.fetchedAt("http://agent-a")).isEmpty();
        cache.resolve("http://agent-a");
        assertThat(cache.fetchedAt("http://agent-a")).contains(T0);

        clock.advance(TTL.plusSeconds(1));
        assertThat(cache.fetchedAt("http://agent-a")).isEmpty();
    }

    private static AgentCard card(int index) {
        return AgentCard.builder()
                .name("Agent " + index)
                .description("Test agent")
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(List.of("text"))
                .defaultOutputModes(List.of("text"))
                .skills(List.of(AgentSkill.builder()
                        .id("skill")
                        .name("Skill")
                        .description("Test skill")
                        .tags(List.of("test"))
                        .examples(List.of())
                        .build()))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC",
                        "http://localhost:8080", null, "1.0")))
                .build();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
