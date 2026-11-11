package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;

/**
 * Classifies errors into transient, permanent, correctable, or critical
 * (FR-2.1, FR-2.3).
 */
public interface ErrorClassifier {

    /** Classifies an error. */
    ErrorType classify(Throwable error, ErrorContext context);

    /** Error types. */
    enum ErrorType {
        /** Network timeouts, HTTP 429, HTTP 5xx — retry with backoff. */
        TRANSIENT,
        /** HTTP 403, HTTP 400, syntax errors — no retry, escalate. */
        PERMANENT,
        /** Wrong file path, invalid JSON params — triage via LLM. */
        CORRECTABLE,
        /** Endless loop, repeated tool calls — hard abort. */
        CRITICAL
    }

    /** Context for classification. */
    record ErrorContext(
            String toolName,
            String errorMessage,
            int consecutiveSameCallCount) {}
}
