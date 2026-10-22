package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;

import java.util.ArrayList;
import java.util.List;

/** Maps MCP result content to the durable tool content list (W-77/W-78). */
public final class McpContentMapper {

    public List<ToolContent> map(JsonValue result) {
        if (!(result instanceof JsonValue.Obj object)
                || !(object.values().get("content") instanceof JsonValue.Arr content)) {
            return List.of();
        }
        List<ToolContent> mapped = new ArrayList<>();
        for (JsonValue item : content.values()) {
            if (!(item instanceof JsonValue.Obj entry)) continue;
            String type = string(entry, "type");
            if ("text".equals(type)) {
                mapped.add(new ToolContent.TextItem(string(entry, "text") == null ? "" : string(entry, "text")));
            } else if ("image".equals(type) || "resource".equals(type)) {
                String uri = string(entry, "uri");
                String mime = string(entry, "mimeType");
                if (uri != null && mime != null) {
                    mapped.add(new ToolContent.FileItem(uri, mime, Option.of(string(entry, "name"))));
                }
            }
        }
        return List.copyOf(mapped);
    }

    public boolean isError(JsonValue result) {
        return result instanceof JsonValue.Obj object
                && object.values().get("isError") instanceof JsonValue.Bool error
                && error.value();
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
    }
}
