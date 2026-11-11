package de.augmentia.oc.engine.runtime.resilience;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

/**
 * Default implementation of {@link ErrorClassifier}.
 *
 * <p>Uses exception type and HTTP status code patterns to classify errors.
 * Extend by overriding {@link #classify} for domain-specific rules.</p>
 */
public final class DefaultErrorClassifier implements ErrorClassifier {

    private final int loopThreshold;

    public DefaultErrorClassifier() {
        this(5);
    }

    public DefaultErrorClassifier(int loopThreshold) {
        this.loopThreshold = Math.max(2, loopThreshold);
    }

    @Override
    public ErrorType classify(Throwable error, ErrorContext context) {
        if (error == null) return ErrorType.PERMANENT;

        // Loop detection
        if (context != null && context.consecutiveSameCallCount() >= loopThreshold) {
            return ErrorType.CRITICAL;
        }

        String msg = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        String cls = error.getClass().getSimpleName().toLowerCase();

        // Transient: timeouts, rate limits, 5xx
        if (error instanceof HttpTimeoutException || error instanceof IOException) {
            if (msg.contains("timeout") || msg.contains("connection") || msg.contains("reset")) {
                return ErrorType.TRANSIENT;
            }
        }
        if (msg.contains("429") || msg.contains("too many requests") || msg.contains("rate limit")) {
            return ErrorType.TRANSIENT;
        }
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")) {
            return ErrorType.TRANSIENT;
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return ErrorType.TRANSIENT;
        }

        // Permanent: 403, 400, 401, syntax errors
        if (msg.contains("403") || msg.contains("forbidden") || msg.contains("permission denied")) {
            return ErrorType.PERMANENT;
        }
        if (msg.contains("400") || msg.contains("bad request")) {
            return ErrorType.PERMANENT;
        }
        if (msg.contains("401") || msg.contains("unauthorized")) {
            return ErrorType.PERMANENT;
        }
        if (msg.contains("syntax") || msg.contains("keyerror") || msg.contains("key error")) {
            return ErrorType.CORRECTABLE;
        }
        if (cls.contains("illegalargument") || cls.contains("illegalstate")) {
            return ErrorType.CORRECTABLE;
        }

        // Default: permanent (fail-safe)
        return ErrorType.PERMANENT;
    }
}
