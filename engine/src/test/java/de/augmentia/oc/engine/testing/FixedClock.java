package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.port.runtime.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Fixed time; {@code sleep} advances the fake clock immediately. */
public final class FixedClock implements Clock {

    private Instant now;
    private final List<Duration> sleeps = new ArrayList<>();

    public FixedClock() {
        this(Instant.EPOCH);
    }

    public FixedClock(Instant now) {
        this.now = now == null ? Instant.EPOCH : now;
    }

    @Override
    public synchronized Instant now() {
        return now;
    }

    @Override
    public synchronized void sleep(Duration duration) {
        if (duration == null) throw new IllegalArgumentException("duration must not be null");
        sleeps.add(duration);
        now = now.plus(duration);
    }

    public synchronized List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }

    public synchronized void advance(Duration duration) {
        now = now.plus(duration);
    }

    public synchronized void set(Instant instant) {
        now = instant;
    }
}
