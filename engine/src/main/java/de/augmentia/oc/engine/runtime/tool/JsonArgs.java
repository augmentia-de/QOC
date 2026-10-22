package de.augmentia.oc.engine.runtime.tool;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;

/** Small, strict readers for the JSON argument objects of the built-in tools. */
public final class JsonArgs {

    private JsonArgs() {}

    public static String string(JsonValue value, String key) {
        if (value instanceof JsonValue.Obj object
                && object.values().get(key) instanceof JsonValue.Str str) {
            return str.value();
        }
        return null;
    }

    public static String requiredString(JsonValue value, String key) {
        String result = string(value, key);
        if (result == null) throw new IllegalArgumentException("missing field: " + key);
        return result;
    }

    public static Option<String> optionalString(JsonValue value, String key) {
        return Option.of(string(value, key));
    }

    public static int intOr(JsonValue value, String key, int fallback) {
        if (value instanceof JsonValue.Obj object
                && object.values().get(key) instanceof JsonValue.Num num) {
            return num.value().intValue();
        }
        return fallback;
    }

    public static boolean boolOr(JsonValue value, String key, boolean fallback) {
        if (value instanceof JsonValue.Obj object
                && object.values().get(key) instanceof JsonValue.Bool bool) {
            return bool.value();
        }
        return fallback;
    }

    public static JsonValue field(JsonValue value, String key) {
        if (value instanceof JsonValue.Obj object && object.values().containsKey(key)) {
            return object.values().get(key);
        }
        return JsonValue.ofNull();
    }
}
