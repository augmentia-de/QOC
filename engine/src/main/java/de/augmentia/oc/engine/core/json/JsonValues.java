package de.augmentia.oc.engine.core.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bridges untyped Java JSON trees (Jackson) and the engine's {@link JsonValue}. */
public final class JsonValues {

    private JsonValues() {}

    public static JsonValue fromJava(Object value) {
        return switch (value) {
            case null -> JsonValue.ofNull();
            case JsonValue json -> json;
            case Boolean bool -> JsonValue.of(bool);
            case Number number -> JsonValue.of(new BigDecimal(number.toString()));
            case String text -> JsonValue.of(text);
            case Map<?, ?> map -> {
                Map<String, JsonValue> values = new LinkedHashMap<>();
                map.forEach((key, item) -> values.put(String.valueOf(key), fromJava(item)));
                yield JsonValue.of(values);
            }
            case Iterable<?> items -> {
                List<JsonValue> values = new ArrayList<>();
                items.forEach(item -> values.add(fromJava(item)));
                yield JsonValue.of(values);
            }
            case Object[] items -> {
                List<JsonValue> values = new ArrayList<>();
                for (Object item : items) values.add(fromJava(item));
                yield JsonValue.of(values);
            }
            default -> JsonValue.of(String.valueOf(value));
        };
    }

    public static Object toJava(JsonValue value) {
        if (value == null) return null;
        return switch (value) {
            case JsonValue.Null ignored -> null;
            case JsonValue.Bool bool -> bool.value();
            case JsonValue.Num number -> number.value();
            case JsonValue.Str text -> text.value();
            case JsonValue.Arr array -> {
                List<Object> values = new ArrayList<>(array.values().size());
                for (JsonValue item : array.values()) values.add(toJava(item));
                yield values;
            }
            case JsonValue.Obj object -> {
                Map<String, Object> values = new LinkedHashMap<>();
                object.values().forEach((key, item) -> values.put(key, toJava(item)));
                yield values;
            }
        };
    }
}
