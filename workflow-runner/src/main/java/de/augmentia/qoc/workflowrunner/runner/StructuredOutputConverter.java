package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.EngineResult;
import de.augmentia.oc.engine.core.model.Option;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Konvertiert {@link EngineResult#structured()} (Option&lt;JsonValue&gt;) in eine
 * {@code Map<String, Object>} für den {@link WorkflowContext}.
 */
public final class StructuredOutputConverter {

    private StructuredOutputConverter() {}

    /**
     * Konvertiert {@code Option<JsonValue>} in eine Map.
     * Gibt {@code null} zurück wenn kein strukturiertes Ergebnis vorhanden.
     */
    public static Map<String, Object> toMap(Option<JsonValue> structured) {
        if (structured == null || structured.isEmpty()) return null;
        JsonValue val = structured.orElseThrow();
        if (val instanceof JsonValue.Obj obj) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, JsonValue> e : obj.values().entrySet()) {
                map.put(e.getKey(), toJavaObject(e.getValue()));
            }
            return map;
        }
        return null;
    }

    /**
     * Konvertiert einen einzelnen {@link JsonValue} in ein Java-Objekt.
     */
    public static Object toJavaObject(JsonValue val) {
        if (val == null) return null;
        return switch (val) {
            case JsonValue.Str s -> s.value();
            case JsonValue.Num n -> n.value();
            case JsonValue.Bool b -> b.value();
            case JsonValue.Null n -> null;
            case JsonValue.Arr a -> {
                List<Object> list = new ArrayList<>();
                for (JsonValue v : a.values()) {
                    list.add(toJavaObject(v));
                }
                yield list;
            }
            case JsonValue.Obj o -> {
                Map<String, Object> m = new HashMap<>();
                for (Map.Entry<String, JsonValue> e : o.values().entrySet()) {
                    m.put(e.getKey(), toJavaObject(e.getValue()));
                }
                yield m;
            }
        };
    }
}