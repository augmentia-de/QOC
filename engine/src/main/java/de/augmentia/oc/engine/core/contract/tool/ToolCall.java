package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.json.*;
import de.augmentia.oc.engine.core.model.*;

public record ToolCall(CallId id, ToolName name, JsonValue input) {
    public ToolCall {
        if (id == null || name == null) throw new IllegalArgumentException("tool call id and name must not be null");
        if (input == null) input = JsonValue.ofNull();
    }
}