package de.augmentia.oc.engine.adapter.time;

import de.augmentia.oc.engine.core.error.Aborted;
import de.augmentia.oc.engine.core.port.runtime.Clock;

import java.time.Duration;
import java.time.Instant;

/** System clock; sleep runs on the calling (virtual) thread. */
public final class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public void sleep(Duration duration) {
        if (duration == null) throw new IllegalArgumentException("duration must not be null");
        if (duration.isNegative() || duration.isZero()) return;
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Aborted("interrupted");
        }
    }
}
