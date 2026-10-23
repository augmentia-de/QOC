package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-RPC 2.0 helpers for the MCP protocol. */
public final class McpJsonRpc {

    private McpJsonRpc() {}

    public static String request(int id, String method, JsonValue params) {
        Map<String, JsonValue> body = new LinkedHashMap<>();
        body.put("jsonrpc", JsonValue.of("2.0"));
        body.put("id", JsonValue.of(id));
        body.put("method", JsonValue.of(method));
        if (params != null) body.put("params", params);
        return Json.encode(JsonValue.of(body));
    }

    public static String initialize() {
        return request(0, "initialize", JsonValue.of(Map.of(
                "protocolVersion", JsonValue.of("2025-11-25"),
                "capabilities", JsonValue.of(Map.of()),
                "clientInfo", JsonValue.of(Map.of(
                        "name", JsonValue.of("opencode"),
                        "version", JsonValue.of("1.0"))))));
    }

    public static String toolsList() {
        return request(1, "tools/list", JsonValue.of(Map.of()));
    }

    public static Result<JsonValue> parse(String body) {
        if (body == null || body.isBlank()) {
            return new Result.Err<>(new de.augmentia.oc.engine.core.json.DecodeError("$", "empty response"));
        }
        return Json.parse(body);
    }

    /** Returns the error message if the response is an error, otherwise empty. */
    public static Option<String> error(JsonValue response) {
        if (response instanceof JsonValue.Obj object
                && object.values().get("error") instanceof JsonValue.Obj error) {
            String message = error.values().get("message") instanceof JsonValue.Str str
                    ? str.value() : null;
            return Option.of(message == null ? "mcp error" : message);
        }
        return Option.none();
    }

    /** Returns the result object if present, otherwise null. */
    public static JsonValue result(JsonValue response) {
        if (response instanceof JsonValue.Obj object) {
            return object.values().get("result");
        }
        return null;
    }

    /** Parses the result of tools/list into tool specs. */
    public static List<McpRegistrar.McpToolSpec> tools(JsonValue result) {
        if (!(result instanceof JsonValue.Obj object)) return List.of();
        if (!(object.values().get("tools") instanceof JsonValue.Arr array)) return List.of();
        List<McpRegistrar.McpToolSpec> tools = new ArrayList<>();
        for (JsonValue item : array.values()) {
            if (!(item instanceof JsonValue.Obj tool)) continue;
            String name = string(tool, "name");
            if (name == null || name.isBlank()) continue;
            String description = string(tool, "description");
            if (description == null) description = "";
            Schema inputSchema = schema(tool.values().get("inputSchema"));
            tools.add(new McpRegistrar.McpToolSpec(name, description, inputSchema));
        }
        return List.copyOf(tools);
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
    }

    private static Schema schema(JsonValue value) {
        if (value == null || value instanceof JsonValue.Null) return Schema.obj(Map.of());
        // Minimal JSON Schema -> declarative Schema mapping.
        if (value instanceof JsonValue.Obj object) {
            if (object.values().get("type") instanceof JsonValue.Str str && "object".equals(str.value())) {
                Map<String, Schema> properties = new java.util.LinkedHashMap<>();
                if (object.values().get("properties") instanceof JsonValue.Obj props) {
                    for (Map.Entry<String, JsonValue> entry : props.values().entrySet()) {
                        properties.put(entry.getKey(), schema(entry.getValue()));
                    }
                }
                boolean additional = object.values().get("additionalProperties") instanceof JsonValue.Bool b && b.value();
                return Schema.obj(properties, additional);
            }
            if (object.values().get("type") instanceof JsonValue.Str str) {
                return switch (str.value()) {
                    case "string" -> Schema.str();
                    case "integer" -> Schema.intg();
                    case "number" -> Schema.num();
                    case "boolean" -> Schema.bool();
                    case "array" -> {
                        JsonValue items = object.values().get("items");
                        yield Schema.arr(items == null ? Schema.any() : schema(items));
                    }
                    default -> Schema.any();
                };
            }
            if (object.values().containsKey("enum")) return Schema.any();
            return Schema.any();
        }
        return Schema.any();
    }
}
