package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;

/**
 * Input of an undo tool call. {@code sessionId} is optional; when absent the
 * session of the current tool context is used.
 */
public record UndoRequest(Option<String> sessionId, String callId) {

    public UndoRequest {
        if (sessionId == null) sessionId = Option.none();
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
    }

    /** Parses the tool input; empty when {@code callId} is missing or not a string. */
    public static Option<UndoRequest> from(JsonValue input) {
        if (!(input instanceof JsonValue.Obj object)) return Option.none();
        JsonValue callId = object.values().get("callId");
        if (!(callId instanceof JsonValue.Str text) || text.value().isBlank()) {
            return Option.none();
        }
        JsonValue sessionId = object.values().get("sessionId");
        Option<String> session = sessionId instanceof JsonValue.Str sid && !sid.value().isBlank()
                ? Option.some(sid.value()) : Option.none();
        return Option.some(new UndoRequest(session, text.value()));
    }
}
