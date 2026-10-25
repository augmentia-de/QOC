package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates the engine's declarative schema into OpenAI's JSON Schema.
 *
 * <p>In strict mode the schema follows OpenAI's strict function-calling rules:
 * every object property is required, optional properties are represented as
 * {@code anyOf: [inner, null]}, and {@code additionalProperties} is always
 * {@code false}. This keeps tool arguments schema-adherent on models that
 * support strict function calling.</p>
 */
public final class OpenAiSchema {

    public JsonValue encode(Schema schema) {
        return encode(schema, false);
    }

    public JsonValue encode(Schema schema, boolean strict) {
        return switch (schema) {
            case Schema.Str ignored -> type("string");
            case Schema.Int ignored -> type("integer");
            case Schema.Long ignored -> type("integer");
            case Schema.Bool ignored -> type("boolean");
            case Schema.Num ignored -> type("number");
            case Schema.Any ignored -> JsonValue.of(Map.of());
            case Schema.Arr array -> JsonValue.of(Map.of(
                    "type", JsonValue.of("array"),
                    "items", encode(array.item(), strict)));
            case Schema.Opt optional -> strict
                    ? nullable(encode(optional.inner(), true))
                    : encode(optional.inner(), false);
            case Schema.Enum enumeration -> JsonValue.of(Map.of(
                    "type", JsonValue.of("string"),
                    "enum", JsonValue.of(enumeration.values().stream()
                            .map(JsonValue::of).toList())));
            case Schema.Union union -> JsonValue.of(Map.of(
                    "anyOf", JsonValue.of(union.options().stream()
                            .map(option -> encode(option, strict)).toList())));
            case Schema.Obj object -> object(object, strict);
        };
    }

    private JsonValue object(Schema.Obj object, boolean strict) {
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Schema> field : object.fields().entrySet()) {
            Schema schema = field.getValue();
            if (strict) {
                // Every property must be required. Optionality is expressed via
                // a nullable type so the model either supplies a value or null.
                properties.put(field.getKey(), encode(schema, true));
                required.add(field.getKey());
            } else if (schema instanceof Schema.Opt optional) {
                properties.put(field.getKey(), encode(optional.inner(), false));
            } else {
                properties.put(field.getKey(), encode(schema, false));
                required.add(field.getKey());
            }
        }
        Map<String, JsonValue> result = new LinkedHashMap<>();
        result.put("type", JsonValue.of("object"));
        result.put("properties", JsonValue.of(properties));
        if (!required.isEmpty()) {
            result.put("required", JsonValue.of(required.stream().map(JsonValue::of).toList()));
        }
        result.put("additionalProperties", JsonValue.of(strict ? false : object.additionalProperties()));
        return JsonValue.of(result);
    }

    private static JsonValue nullable(JsonValue inner) {
        return JsonValue.of(Map.of(
                "anyOf", JsonValue.of(List.of(inner, type("null")))));
    }

    private static JsonValue type(String name) {
        return JsonValue.of(Map.of("type", JsonValue.of(name)));
    }
}
