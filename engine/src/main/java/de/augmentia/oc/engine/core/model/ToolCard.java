package de.augmentia.oc.engine.core.model;
import de.augmentia.oc.engine.core.json.*;

import java.time.Instant;

public record ToolCard(CallId id, ToolName name, JsonValue input, ToolState state,
                       boolean executed, Instant time) {
    public ToolCard {
        if (id == null || name == null || state == null) {
            throw new IllegalArgumentException("tool card id, name and state must not be null");
        }
        if (input == null) input = JsonValue.ofNull();
        if (time == null) time = Instant.EPOCH;
    }
}