package de.augmentia.oc.engine.runtime.tool.undo;

import de.augmentia.oc.engine.core.model.Option;

public record UndoRequest(Option<String> sessionId, String callId) {

    public UndoRequest {
        if (sessionId == null) sessionId = Option.none();
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
    }
}
