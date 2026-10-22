package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Translates the engine's declarative schema into OpenAI's JSON Schema. */
public final class OpenAiSchema {

    public JsonValue encode(Schema schema) {
        return switch (schema) {
            case Schema.Str ignored -> type("string");
            case Schema.Int ignored -> type("integer");
            case Schema.Long ignored -> type("integer");
            case Schema.Bool ignored -> type("boolean");
            case Schema.Num ignored -> type("number");
            case Schema.Any ignored -> JsonValue.of(Map.of());
            case Schema.Arr array -> JsonValue.of(Map.of(
                    "type", JsonValue.of("array"),
                    "items", encode(array.item())));
            case Schema.Opt optional -> encode(optional.inner());
            case Schema.Enum enumeration -> JsonValue.of(Map.of(
                    "type", JsonValue.of("string"),
                    "enum", JsonValue.of(enumeration.values().stream()
                            .map(JsonValue::of).toList())));
            case Schema.Union union -> JsonValue.of(Map.of(
                    "anyOf", JsonValue.of(union.options().stream().map(this::encode).toList())));
            case Schema.Obj object -> object(object);
        };
    }

    private JsonValue object(Schema.Obj object) {
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Schema> field : object.fields().entrySet()) {
            Schema schema = field.getValue();
            if (schema instanceof Schema.Opt optional) {
                properties.put(field.getKey(), encode(optional.inner()));
            } else {
                properties.put(field.getKey(), encode(schema));
                required.add(field.getKey());
            }
        }
        Map<String, JsonValue> result = new LinkedHashMap<>();
        result.put("type", JsonValue.of("object"));
        result.put("properties", JsonValue.of(properties));
        if (!required.isEmpty()) {
            result.put("required", JsonValue.of(required.stream().map(JsonValue::of).toList()));
        }
        result.put("additionalProperties", JsonValue.of(object.additionalProperties()));
        return JsonValue.of(result);
    }

    private static JsonValue type(String name) {
        return JsonValue.of(Map.of("type", JsonValue.of(name)));
    }
}
