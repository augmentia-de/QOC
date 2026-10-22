package de.augmentia.oc.engine.core.json;

import java.util.List;
import java.util.Map;

public sealed interface Schema {
    record Str() implements Schema {}
    record Int() implements Schema {}
    record Long() implements Schema {}
    record Bool() implements Schema {}
    record Num() implements Schema {}
    record Arr(Schema item) implements Schema {}
    record Obj(Map<String, Schema> fields, boolean additionalProperties) implements Schema {}
    record Opt(Schema inner) implements Schema {}
    record Enum(List<String> values) implements Schema {}
    record Union(List<Schema> options) implements Schema {}
    record Any() implements Schema {}

    static Schema str() { return new Str(); }
    static Schema string() { return new Str(); }
    static Schema intg() { return new Int(); }
    static Schema integer() { return new Int(); }
    static Schema lng() { return new Long(); }
    static Schema longg() { return new Long(); }
    static Schema bool() { return new Bool(); }
    static Schema boolean_() { return new Bool(); }
    static Schema num() { return new Num(); }
    static Schema number() { return new Num(); }
    static Schema arr(Schema item) { return new Arr(item); }
    static Schema array(Schema item) { return new Arr(item); }
    static Schema obj(Map<String, Schema> fields) { return new Obj(Map.copyOf(fields), false); }
    static Schema object(Map<String, Schema> fields) { return new Obj(Map.copyOf(fields), false); }
    static Schema obj(Map<String, Schema> fields, boolean additional) { return new Obj(Map.copyOf(fields), additional); }
    static Schema object(Map<String, Schema> fields, boolean additional) { return new Obj(Map.copyOf(fields), additional); }
    static Schema opt(Schema inner) { return new Opt(inner); }
    static Schema optional(Schema inner) { return new Opt(inner); }
    static Schema enm(List<String> values) { return new Enum(List.copyOf(values)); }
    static Schema enumeration(List<String> values) { return new Enum(List.copyOf(values)); }
    static Schema union(List<Schema> options) { return new Union(List.copyOf(options)); }
    static Schema any() { return new Any(); }
}