package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.testing.FixedClock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link Clock}. */
class ClockContract {

    @Test
    void nowReturnsTheFixedTime() {
        FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void sleepAdvancesTheFakeTimeImmediately() {
        FixedClock clock = new FixedClock(Instant.EPOCH);
        clock.sleep(Duration.ofSeconds(3));
        assertThat(clock.now()).isEqualTo(Instant.ofEpochSecond(3));
        assertThat(clock.sleeps()).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void deadlineCountsDownAndExpires() {
        FixedClock clock = new FixedClock(Instant.EPOCH);
        Clock.Deadline deadline = clock.deadline(Duration.ofSeconds(10));

        assertThat(deadline.expired()).isFalse();
        assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(10));

        clock.advance(Duration.ofSeconds(4));
        assertThat(deadline.remaining()).isEqualTo(Duration.ofSeconds(6));

        clock.advance(Duration.ofSeconds(6));
        assertThat(deadline.expired()).isTrue();
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }
}
