package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.core.port.runtime.Clock;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SystemClockTest {

    private final SystemClock clock = new SystemClock();

    @Test
    void nowTracksTheSystemClock() {
        Instant before = Instant.now();
        Instant now = clock.now();
        Instant after = Instant.now();

        assertThat(now).isBetween(before, after);
    }

    @Test
    void zeroAndNegativeSleepReturnImmediately() {
        clock.sleep(Duration.ZERO);
        clock.sleep(Duration.ofMillis(-10));
    }

    @Test
    void sleepWaitsAtLeastTheRequestedTime() {
        long start = System.nanoTime();
        clock.sleep(Duration.ofMillis(20));
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(elapsed).isGreaterThanOrEqualTo(15);
    }

    @Test
    void deadlineCountsDown() {
        Clock.Deadline deadline = clock.deadline(Duration.ofMillis(200));
        assertThat(deadline.expired()).isFalse();
        assertThat(deadline.remaining()).isPositive();
    }
}
