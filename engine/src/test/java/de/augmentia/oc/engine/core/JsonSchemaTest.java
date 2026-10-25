package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonSchema;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaTest {

    @Test
    void objectMarksRequiredAndOptionalProperties() {
        Schema.Obj schema = (Schema.Obj) parse("""
                {
                  "type": "object",
                  "properties": {
                    "name": {"type": "string"},
                    "age": {"type": "integer"}
                  },
                  "required": ["name"]
                }
                """);
        assertThat(schema.fields().get("name")).isInstanceOf(Schema.Str.class);
        assertThat(schema.fields().get("age")).isInstanceOf(Schema.Opt.class);
        assertThat(((Schema.Opt) schema.fields().get("age")).inner()).isInstanceOf(Schema.Int.class);
        assertThat(schema.additionalProperties()).isFalse();
    }

    @Test
    void additionalPropertiesIsRead() {
        Schema.Obj schema = (Schema.Obj) parse("""
                {"type": "object", "properties": {}, "additionalProperties": true}
                """);
        assertThat(schema.additionalProperties()).isTrue();
    }

    @Test
    void nullableTypeArrayBecomesOptional() {
        Schema.Opt schema = (Schema.Opt) parse("""
                {"type": ["string", "null"]}
                """);
        assertThat(schema.inner()).isInstanceOf(Schema.Str.class);
    }

    @Test
    void optionalNullablePropertyIsNotDoubleWrapped() {
        Schema.Obj schema = (Schema.Obj) parse("""
                {
                  "type": "object",
                  "properties": {"note": {"type": ["string", "null"]}}
                }
                """);
        assertThat(schema.fields().get("note")).isInstanceOf(Schema.Opt.class);
        assertThat(((Schema.Opt) schema.fields().get("note")).inner())
                .isInstanceOf(Schema.Str.class);
    }

    @Test
    void anyOfWithNullBecomesOptional() {
        Schema.Opt schema = (Schema.Opt) parse("""
                {"anyOf": [{"type": "string"}, {"type": "null"}]}
                """);
        assertThat(schema.inner()).isInstanceOf(Schema.Str.class);
    }

    @Test
    void oneOfWithoutNullBecomesUnion() {
        Schema.Union schema = (Schema.Union) parse("""
                {"oneOf": [{"type": "string"}, {"type": "integer"}]}
                """);
        assertThat(schema.options()).hasSize(2);
    }

    @Test
    void enumAndConstAreParsed() {
        Schema.Enum enumeration = (Schema.Enum) parse("""
                {"type": "string", "enum": ["a", "b"]}
                """);
        assertThat(enumeration.values()).containsExactly("a", "b");

        Schema.Enum constant = (Schema.Enum) parse("""
                {"const": "fixed"}
                """);
        assertThat(constant.values()).containsExactly("fixed");
    }

    @Test
    void arraysParseItems() {
        Schema.Arr schema = (Schema.Arr) parse("""
                {"type": "array", "items": {"type": "number"}}
                """);
        assertThat(schema.item()).isInstanceOf(Schema.Num.class);
    }

    @Test
    void missingTypeWithPropertiesIsAnObject() {
        Schema.Obj schema = (Schema.Obj) parse("""
                {"properties": {"name": {"type": "string"}}}
                """);
        assertThat(schema.fields()).containsKey("name");
    }

    @Test
    void emptySchemaIsAny() {
        assertThat(parse("{}")).isInstanceOf(Schema.Any.class);
    }

    @Test
    void primitiveTypesAreParsed() {
        assertThat(parse("{\"type\":\"string\"}")).isInstanceOf(Schema.Str.class);
        assertThat(parse("{\"type\":\"number\"}")).isInstanceOf(Schema.Num.class);
        assertThat(parse("{\"type\":\"boolean\"}")).isInstanceOf(Schema.Bool.class);
        assertThat(parse("{\"type\":\"null\"}")).isInstanceOf(Schema.Opt.class);
    }

    @Test
    void unsupportedKeywordsAreRejected() {
        assertError("{\"$ref\":\"#/x\"}", "$ref is not supported");
        assertError("{\"allOf\":[]}", "allOf is not supported");
        assertError("{\"not\":{}}", "not is not supported");
    }

    @Test
    void nonObjectSchemaIsRejected() {
        assertError("\"string\"", "schema must be an object");
    }

    @Test
    void unsupportedTypeIsRejected() {
        assertError("{\"type\":\"unknown\"}", "unsupported type: unknown");
        assertError("{\"type\":42}", "type must be a string or an array of strings");
    }

    @Test
    void malformedRequiredIsRejected() {
        assertError("{\"type\":\"object\",\"required\":\"name\"}",
                "required must be an array of strings");
        assertError("{\"type\":\"object\",\"required\":[42]}",
                "required entries must be strings");
        assertError("{\"type\":\"object\",\"required\":[\"missing\"]}",
                "required property is not defined: missing");
    }

    @Test
    void malformedPropertiesAreRejected() {
        assertError("{\"type\":\"object\",\"properties\":[]}",
                "properties must be an object");
    }

    @Test
    void malformedAdditionalPropertiesIsRejected() {
        assertError("{\"type\":\"object\",\"additionalProperties\":{}}",
                "only boolean additionalProperties is supported");
    }

    @Test
    void tupleItemsAreRejected() {
        assertError("{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}",
                "tuple items are not supported");
    }

    @Test
    void malformedEnumIsRejected() {
        assertError("{\"enum\":[]}", "enum must not be empty");
        assertError("{\"enum\":[42]}", "only string enum values are supported");
    }

    @Test
    void malformedTypeArraysAreRejected() {
        assertError("{\"type\":[]}", "type must not be empty");
        assertError("{\"type\":[42]}", "type must be a string");
        assertError("{\"type\":[\"unknown\"]}", "unsupported type: unknown");
    }

    @Test
    void emptyUnionIsRejected() {
        assertError("{\"anyOf\":[]}", "union must not be empty");
    }

    private static Schema parse(String text) {
        Result<Schema> parsed = JsonSchema.parse(json(text));
        assertThat(parsed).isInstanceOf(Result.Ok.class);
        return parsed.valueOrNull();
    }

    private static void assertError(String text, String message) {
        Result<Schema> parsed = JsonSchema.parse(json(text));
        assertThat(parsed).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<Schema>) parsed).error().message()).isEqualTo(message);
    }

    private static JsonValue json(String text) {
        Result<JsonValue> parsed = Json.parse(text);
        assertThat(parsed).isInstanceOf(Result.Ok.class);
        return parsed.valueOrNull();
    }
}
