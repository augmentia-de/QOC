package de.augmentia.oc.engine.core.json;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public sealed interface JsonValue {
    record Null() implements JsonValue {}
    record Bool(boolean value) implements JsonValue {}
    record Num(BigDecimal value) implements JsonValue {}
    record Str(String value) implements JsonValue {}
    record Arr(List<JsonValue> values) implements JsonValue {}
    record Obj(Map<String, JsonValue> values) implements JsonValue {}

    static JsonValue ofNull() { return new Null(); }
    static JsonValue of(boolean v) { return new Bool(v); }
    static JsonValue of(long v) { return new Num(BigDecimal.valueOf(v)); }
    static JsonValue of(double v) { return new Num(BigDecimal.valueOf(v)); }
    static JsonValue of(BigDecimal v) { return new Num(v); }
    static JsonValue of(String v) { return new Str(v); }
    static JsonValue of(List<JsonValue> v) { return new Arr(List.copyOf(v)); }
    static JsonValue of(Map<String, JsonValue> v) { return new Obj(Map.copyOf(v)); }
}