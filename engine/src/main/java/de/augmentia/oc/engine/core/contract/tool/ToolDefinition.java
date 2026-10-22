package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.json.*;
import de.augmentia.oc.engine.core.model.*;

public record ToolDefinition(
        ToolName name,
        String description,
        Schema inputSchema,
        Option<Schema> outputSchema,
        ToolExecutor execute,
        ToolOptions options) {

    public ToolDefinition {
        if (name == null) throw new IllegalArgumentException("tool name must not be null");
        if (description == null) description = "";
        if (inputSchema == null) throw new IllegalArgumentException("input schema must not be null");
        if (outputSchema == null) outputSchema = Option.none();
        if (execute == null) throw new IllegalArgumentException("executor must not be null");
        if (options == null) options = ToolOptions.defaults();
    }

    public String effectiveName() {
        String normalizedName = normalize(name.value());
        if (options.namespace().isPresent()) {
            return normalize(options.namespace().orElse("")) + "_" + normalizedName;
        }
        return normalizedName;
    }

    private static String normalize(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}