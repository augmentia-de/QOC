package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.contract.provider.TransportOperation;
import de.augmentia.oc.engine.core.model.Option;

import java.time.Duration;

/**
 * Retryability is decided by the closed failure class, never by the message
 * text (provider/03-classification.md).
 */
public final class ProviderClassifier {

    public static final int DEFAULT_MAX_ATTEMPTS = 4;
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private ProviderClassifier() {}

    /** The retryability matrix of provider/03 §3. */
    public static boolean isRetryable(FailureKind kind) {
        if (kind == null) return false;
        return switch (kind) {
            case FailureKind.RateLimited ignored -> true;
            case FailureKind.ProviderInternal ignored -> true;
            case FailureKind.Timeout ignored -> true;
            case FailureKind.InvalidOutput output ->
                    output.classification() == InvalidOutputClassification.INCOMPLETE_STREAM;
            case FailureKind.InvalidRequest request ->
                    request.classification() == InvalidRequestClassification.CONTEXT_OVERFLOW;
            case FailureKind.Transport transport -> isRetryable(transport);
            case FailureKind.Authentication ignored -> false;
            case FailureKind.QuotaExceeded ignored -> false;
            case FailureKind.ContentPolicy ignored -> false;
            case FailureKind.UnsupportedOperation ignored -> false;
            case FailureKind.NoRoute ignored -> false;
            case FailureKind.UnknownProvider ignored -> false;
        };
    }

    /**
     * Transport is conditional: a request that never reached the provider can
     * be retried; a request the provider accepted can be retried only while
     * reading (sending again may duplicate the round).
     */
    public static boolean isRetryable(FailureKind.Transport transport) {
        if (transport == null) return false;
        return switch (transport.delivery()) {
            case UNSENT -> true;
            case ACCEPTED -> transport.operation() == TransportOperation.READ;
            case REJECTED -> false;
        };
    }

    /** Context overflow is not a plain retry: it takes the compaction path. */
    public static boolean needsCompaction(FailureKind kind) {
        return kind instanceof FailureKind.InvalidRequest request
                && request.classification() == InvalidRequestClassification.CONTEXT_OVERFLOW;
    }

    /**
     * Next wait before a retry, or empty when no retry may be started.
     *
     * @param attempt           number of attempts already made (zero-based)
     * @param maxAttempts       hard attempt bound
     * @param retryAfter        provider hint, takes precedence over backoff
     * @param remainingBudget   time budget left for the whole call
     */
    public static Option<Duration> nextDelay(FailureKind kind, int attempt, int maxAttempts,
                                             Option<Duration> retryAfter, Duration remainingBudget) {
        if (kind == null || remainingBudget == null || remainingBudget.isNegative()) return Option.none();
        if (retryAfter == null) retryAfter = Option.none();
        if (needsCompaction(kind)) return Option.none();
        if (!isRetryable(kind)) return Option.none();
        if (attempt < 0 || attempt + 1 >= maxAttempts) return Option.none();

        Duration wait = retryAfter.orElse(backoff(attempt));
        if (wait.compareTo(remainingBudget) > 0) return Option.none();
        return Option.some(wait);
    }

    /** Exponential backoff: 1 s, 2 s, 4 s, 8 s, 16 s, then capped at 30 s. */
    public static Duration backoff(int attempt) {
        if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
        long seconds = 1L << Math.min(attempt, 30);
        return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF.toSeconds()));
    }

    /**
     * Exponential backoff with jitter. {@code random} must be in {@code [0,1)}.
     * The jitter adds up to one second, so sessions spread out instead of
     * retrying in lock-step.
     */
    public static Duration backoff(int attempt, double random) {
        if (random < 0 || random >= 1) throw new IllegalArgumentException("random must be in [0,1)");
        return backoff(attempt).plusMillis((long) (random * 1_000));
    }
}
