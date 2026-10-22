package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.model.*;

import java.util.List;
import java.util.Map;

/** Frozen tool set of one model request. */
public record ToolSnapshot(Map<ToolName, ToolDefinition> definitions) {

    public ToolSnapshot {
        definitions = definitions == null ? Map.of() : Map.copyOf(definitions);
    }

    public static ToolSnapshot empty() {
        return new ToolSnapshot(Map.of());
    }

    public ToolDefinition require(ToolName name) {
        ToolDefinition definition = definitions.get(name);
        if (definition == null) {
            throw new ToolFailure("Tool is not available for this request: " + name);
        }
        return definition;
    }

    public List<ToolDefinition> list() {
        return List.copyOf(definitions.values());
    }

    /** Executes a call against the definition frozen in this snapshot. */
    public ToolOutcome execute(ToolCall call, ToolContext context) {
        if (call == null || context == null) {
            throw new IllegalArgumentException("call and context must not be null");
        }
        return require(call.name()).execute().execute(call.input(), context);
    }
}