package de.augmentia.oc.engine.runtime.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry policy with exponential backoff and jitter (FR-2.2).
 *
 * <p>Formula: {@code t = min(Tmax, Base × 2^attempt) + Jitter(0..JitterMax)}</p>
 */
public interface RetryPolicy {

    /** Returns the delay before the next retry attempt. */
    Duration delay(int attempt, ErrorClassifier.ErrorType type);

    /** Default implementation. */
    final class Default implements RetryPolicy {
        private final long baseDelayMs;
        private final long maxDelayMs;
        private final long jitterMs;

        public Default() {
            this(1000, 60000, 5000);
        }

        public Default(long baseDelayMs, long maxDelayMs, long jitterMs) {
            this.baseDelayMs = Math.max(100, baseDelayMs);
            this.maxDelayMs = Math.max(this.baseDelayMs, maxDelayMs);
            this.jitterMs = Math.max(0, jitterMs);
        }

        @Override
        public Duration delay(int attempt, ErrorClassifier.ErrorType type) {
            if (type == ErrorClassifier.ErrorType.PERMANENT || type == ErrorClassifier.ErrorType.CRITICAL) {
                return Duration.ZERO; // no retry
            }
            long exponential = baseDelayMs * (1L << Math.min(attempt, 10));
            long capped = Math.min(exponential, maxDelayMs);
            long jitter = jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs + 1) : 0;
            return Duration.ofMillis(capped + jitter);
        }
    }
}
