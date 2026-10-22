package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.model.Option;

import java.time.Duration;
import java.util.Set;

/**
 * Classifies HTTP status and structural fields into the closed
 * {@link FailureKind}. It never inspects the message text (V-30, V-31).
 */
public final class ProviderErrorClassifier {

    private static final Set<String> CONTEXT_OVERFLOW_CODES = Set.of(
            "context_length_exceeded", "context_window_exceeded",
            "string_above_max_length", "request_too_large");

    public FailureKind classify(int status, String code, String type, Option<Duration> retryAfter) {
        if (retryAfter == null) retryAfter = Option.none();
        return switch (status) {
            case 401, 403 -> new FailureKind.Authentication(Option.none());
            case 402 -> new FailureKind.QuotaExceeded(Option.none());
            case 429 -> new FailureKind.RateLimited(retryAfter);
            case 400 -> new FailureKind.InvalidRequest(isContextOverflow(code, type)
                    ? InvalidRequestClassification.CONTEXT_OVERFLOW
                    : InvalidRequestClassification.OTHER);
            case 404 -> new FailureKind.NoRoute();
            case 408 -> new FailureKind.Timeout();
            case 413 -> new FailureKind.InvalidRequest(InvalidRequestClassification.CONTEXT_OVERFLOW);
            case 422 -> new FailureKind.ContentPolicy();
            case 501 -> new FailureKind.UnsupportedOperation(code == null ? "" : code);
            default -> status >= 500
                    ? new FailureKind.ProviderInternal(retryAfter)
                    : new FailureKind.InvalidRequest(InvalidRequestClassification.OTHER);
        };
    }

    /** Structural features only: the provider error code/type, never the message. */
    public boolean isContextOverflow(String code, String type) {
        return code != null && CONTEXT_OVERFLOW_CODES.contains(code);
    }
}
