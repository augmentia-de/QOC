package de.augmentia.oc.engine.core.port.runtime;

import java.time.Duration;
import java.time.Instant;

/** Time port: the only place where the engine reads the wall clock. */
public interface Clock {

    Instant now();

    void sleep(Duration duration);

    default Deadline deadline(Duration timeout) {
        if (timeout == null) throw new IllegalArgumentException("timeout must not be null");
        Instant at = now().plus(timeout);
        return new Deadline() {
            @Override
            public Duration remaining() {
                Duration left = Duration.between(now(), at);
                return left.isNegative() ? Duration.ZERO : left;
            }

            @Override
            public boolean expired() {
                return !now().isBefore(at);
            }
        };
    }

    interface Deadline {

        Duration remaining();

        boolean expired();
    }
}
