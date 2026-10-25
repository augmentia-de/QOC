package de.augmentia.oc.engine.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the JSON Schema subset that the engine can validate and encode.
 *
 * <p>Supported: primitive types, objects with {@code properties},
 * {@code required} and boolean {@code additionalProperties}, arrays with a
 * single {@code items} schema, string {@code enum}/{@code const},
 * {@code anyOf}/{@code oneOf} and nullable type arrays. Unsupported keywords
 * fail with an explicit error instead of silently weakening validation.</p>
 *
 * <p>Objects are closed by default; set {@code additionalProperties} to
 * {@code true} explicitly for free-form objects.</p>
 */
public final class JsonSchema {

    private JsonSchema() {}

    public static Result<Schema> parse(JsonValue value) {
        return parse(value, "$");
    }

    private static Result<Schema> parse(JsonValue value, String path) {
        if (!(value instanceof JsonValue.Obj object)) {
            return err(path, "schema must be an object");
        }
        Map<String, JsonValue> fields = object.values();
        if (fields.containsKey("$ref")) return err(path, "$ref is not supported");
        if (fields.containsKey("allOf")) return err(path, "allOf is not supported");
        if (fields.containsKey("not")) return err(path, "not is not supported");

        if (fields.get("anyOf") instanceof JsonValue.Arr branches) {
            return union(branches.values(), path + ".anyOf");
        }
        if (fields.get("oneOf") instanceof JsonValue.Arr branches) {
            return union(branches.values(), path + ".oneOf");
        }
        if (fields.get("enum") instanceof JsonValue.Arr values) {
            return enumSchema(values.values(), path + ".enum");
        }
        if (fields.get("const") instanceof JsonValue.Str constant) {
            return ok(Schema.enm(List.of(constant.value())));
        }

        JsonValue type = fields.get("type");
        if (type == null) {
            // JSON Schema allows omitting type; object-like schemas are common.
            if (fields.get("properties") instanceof JsonValue.Obj) {
                return object(fields, path);
            }
            return ok(Schema.any());
        }
        if (type instanceof JsonValue.Arr types) {
            return types(types.values(), fields, path + ".type");
        }
        if (!(type instanceof JsonValue.Str name)) {
            return err(path + ".type", "type must be a string or an array of strings");
        }
        return byType(name.value(), fields, path);
    }

    private static Result<Schema> byType(String type, Map<String, JsonValue> fields, String path) {
        return switch (type) {
            case "string" -> ok(Schema.str());
            case "integer" -> ok(Schema.intg());
            case "number" -> ok(Schema.num());
            case "boolean" -> ok(Schema.bool());
            case "array" -> array(fields, path);
            case "object" -> object(fields, path);
            case "null" -> ok(Schema.opt(Schema.any()));
            default -> err(path + ".type", "unsupported type: " + type);
        };
    }

    private static Result<Schema> types(List<JsonValue> types, Map<String, JsonValue> fields,
                                        String path) {
        boolean nullable = false;
        List<Schema> options = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if (!(types.get(i) instanceof JsonValue.Str name)) {
                return err(path + "[" + i + "]", "type must be a string");
            }
            if ("null".equals(name.value())) {
                nullable = true;
                continue;
            }
            Result<Schema> parsed = byType(name.value(), fields, path + "[" + i + "]");
            if (parsed instanceof Result.Err<Schema> error) return new Result.Err<>(error.error());
            options.add(parsed.valueOrNull());
        }
        if (options.isEmpty()) {
            return nullable ? ok(Schema.opt(Schema.any())) : err(path, "type must not be empty");
        }
        Schema schema = options.size() == 1 ? options.get(0) : Schema.union(options);
        return ok(nullable ? Schema.opt(schema) : schema);
    }

    private static Result<Schema> object(Map<String, JsonValue> fields, String path) {
        List<String> required = new ArrayList<>();
        JsonValue requiredValue = fields.get("required");
        if (requiredValue instanceof JsonValue.Arr values) {
            for (int i = 0; i < values.values().size(); i++) {
                if (!(values.values().get(i) instanceof JsonValue.Str name)) {
                    return err(path + ".required[" + i + "]", "required entries must be strings");
                }
                required.add(name.value());
            }
        } else if (requiredValue != null && !(requiredValue instanceof JsonValue.Null)) {
            return err(path + ".required", "required must be an array of strings");
        }

        Map<String, Schema> properties = new LinkedHashMap<>();
        JsonValue propertiesValue = fields.get("properties");
        if (propertiesValue instanceof JsonValue.Obj props) {
            for (Map.Entry<String, JsonValue> entry : props.values().entrySet()) {
                Result<Schema> parsed = parse(entry.getValue(),
                        path + ".properties." + entry.getKey());
                if (parsed instanceof Result.Err<Schema> error) {
                    return new Result.Err<>(error.error());
                }
                Schema schema = parsed.valueOrNull();
                if (!required.contains(entry.getKey()) && !(schema instanceof Schema.Opt)) {
                    schema = Schema.opt(schema);
                }
                properties.put(entry.getKey(), schema);
            }
        } else if (propertiesValue != null && !(propertiesValue instanceof JsonValue.Null)) {
            return err(path + ".properties", "properties must be an object");
        }

        for (String name : required) {
            if (!properties.containsKey(name)) {
                return err(path + ".required", "required property is not defined: " + name);
            }
        }

        boolean additional = false;
        JsonValue additionalValue = fields.get("additionalProperties");
        if (additionalValue != null && !(additionalValue instanceof JsonValue.Null)) {
            if (!(additionalValue instanceof JsonValue.Bool bool)) {
                return err(path + ".additionalProperties",
                        "only boolean additionalProperties is supported");
            }
            additional = bool.value();
        }
        return ok(Schema.obj(properties, additional));
    }

    private static Result<Schema> array(Map<String, JsonValue> fields, String path) {
        Schema item = Schema.any();
        JsonValue items = fields.get("items");
        if (items != null && !(items instanceof JsonValue.Null)) {
            if (items instanceof JsonValue.Arr) {
                return err(path + ".items", "tuple items are not supported");
            }
            Result<Schema> parsed = parse(items, path + ".items");
            if (parsed instanceof Result.Err<Schema> error) {
                return new Result.Err<>(error.error());
            }
            item = parsed.valueOrNull();
        }
        return ok(Schema.arr(item));
    }

    private static Result<Schema> enumSchema(List<JsonValue> values, String path) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (!(values.get(i) instanceof JsonValue.Str name)) {
                return err(path + "[" + i + "]", "only string enum values are supported");
            }
            names.add(name.value());
        }
        if (names.isEmpty()) return err(path, "enum must not be empty");
        return ok(Schema.enm(names));
    }

    private static Result<Schema> union(List<JsonValue> branches, String path) {
        boolean nullable = false;
        List<Schema> options = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            if (isNullSchema(branches.get(i))) {
                nullable = true;
                continue;
            }
            Result<Schema> parsed = parse(branches.get(i), path + "[" + i + "]");
            if (parsed instanceof Result.Err<Schema> error) {
                return new Result.Err<>(error.error());
            }
            options.add(parsed.valueOrNull());
        }
        if (options.isEmpty()) {
            return nullable ? ok(Schema.opt(Schema.any())) : err(path, "union must not be empty");
        }
        Schema schema = options.size() == 1 ? options.get(0) : Schema.union(options);
        return ok(nullable ? Schema.opt(schema) : schema);
    }

    private static boolean isNullSchema(JsonValue value) {
        return value instanceof JsonValue.Obj object
                && object.values().get("type") instanceof JsonValue.Str type
                && "null".equals(type.value());
    }

    private static Result<Schema> ok(Schema schema) {
        return new Result.Ok<>(schema);
    }

    private static Result<Schema> err(String path, String message) {
        return new Result.Err<>(new DecodeError(path, message));
    }
}
