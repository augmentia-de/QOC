package de.augmentia.oc.engine.core.model;

import de.augmentia.oc.engine.core.json.JsonValue;

/**
 * Result of a prompt execution in the QOC Engine.
 * Framework-neutral equivalent of Quarkus Engine.PromptResult.
 */
public record EngineResult(
        String text,
        String finish,
        long inputTokens,
        long outputTokens,
        boolean failed,
        String errorMessage,
        boolean retryable,
        boolean paused,
        String approvalId,
        de.augmentia.oc.engine.core.model.Option<JsonValue> structured
) {
    public EngineResult {
        if (text == null) text = "";
        if (finish == null) finish = "COMPLETED";
        if (structured == null) structured = de.augmentia.oc.engine.core.model.Option.none();
    }

    public static EngineResult completed(String text, String finish, long inputTokens, long outputTokens) {
        return new EngineResult(text, finish, inputTokens, outputTokens, false, null, false, false, null,
                de.augmentia.oc.engine.core.model.Option.none());
    }

    public static EngineResult failed(String errorMessage, boolean retryable) {
        return new EngineResult("", "FAILED", 0, 0, true, errorMessage, retryable, false, null,
                de.augmentia.oc.engine.core.model.Option.none());
    }

    public static EngineResult cancelled() {
        return new EngineResult("", "CANCELLED", 0, 0, false, null, false, false, null,
                de.augmentia.oc.engine.core.model.Option.none());
    }

    public static EngineResult paused(String approvalId, String approvalUrl, String message) {
        return new EngineResult(message == null ? "" : message, "PAUSED", 0, 0, false, null, false, true,
                approvalId, de.augmentia.oc.engine.core.model.Option.none());
    }

    public static EngineResult busy(String message) {
        return new EngineResult("", "BUSY", 0, 0, false, message, false, false, null,
                de.augmentia.oc.engine.core.model.Option.none());
    }
}
