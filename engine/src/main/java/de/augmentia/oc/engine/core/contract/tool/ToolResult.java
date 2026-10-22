package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.json.*;
import de.augmentia.oc.engine.core.model.*;

import java.util.List;
import java.util.Map;

public record ToolResult(Option<JsonValue> output, List<ToolContent> content, Map<String, Object> metadata) {

    public ToolResult {
        if (output == null) output = Option.none();
        content = content == null ? List.of() : List.copyOf(content);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolResult of(String text) {
        return new ToolResult(Option.none(), List.of(new ToolContent.TextItem(text)), Map.of());
    }
}