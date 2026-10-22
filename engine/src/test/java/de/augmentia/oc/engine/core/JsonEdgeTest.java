package de.augmentia.oc.engine.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonEdgeTest {

    @Test
    void optionalFieldMissingOk() {
        Result<JsonValue> r = Json.decode("{}", Schema.obj(Map.of("a", Schema.opt(Schema.str()))));
        assertThat(r).isInstanceOf(Result.Ok.class);
    }

    @Test
    void enumValidation() {
        Result<JsonValue> ok = Json.decode("\"b\"", Schema.enm(List.of("a", "b")));
        assertThat(ok).isInstanceOf(Result.Ok.class);
        Result<JsonValue> bad = Json.decode("\"c\"", Schema.enm(List.of("a", "b")));
        assertThat(bad).isInstanceOf(Result.Err.class);
    }

    @Test
    void unionValidation() {
        Result<JsonValue> r = Json.decode("42", Schema.union(List.of(Schema.str(), Schema.intg())));
        assertThat(r).isInstanceOf(Result.Ok.class);
    }

    @Test
    void intRejectsFraction() {
        Result<JsonValue> r = Json.decode("3.14", Schema.intg());
        assertThat(r).isInstanceOf(Result.Err.class);
    }

    @Test
    void parseErrorTrailing() {
        Result<JsonValue> r = Json.parse("1 2");
        assertThat(r).isInstanceOf(Result.Err.class);
    }

    @Test
    void parseNullInput() {
        Result<JsonValue> r = Json.parse(null);
        assertThat(r).isInstanceOf(Result.Err.class);
    }

    @Test
    void decodeJsonValueOverload() {
        JsonValue v = JsonValue.of(true);
        Result<JsonValue> r = Json.decode(v, Schema.bool());
        assertThat(r).isInstanceOf(Result.Ok.class);
    }

    @Test
    void additionalPropertiesAllowed() {
        Result<JsonValue> r = Json.decode("{\"a\":1,\"x\":2}", Schema.obj(Map.of("a", Schema.intg()), true));
        assertThat(r).isInstanceOf(Result.Ok.class);
    }

    @Test
    void arrValidation() {
        Result<JsonValue> r = Json.decode("[1,2]", Schema.arr(Schema.intg()));
        assertThat(r).isInstanceOf(Result.Ok.class);
        Result<JsonValue> bad = Json.decode("[1,\"x\"]", Schema.arr(Schema.intg()));
        assertThat(bad).isInstanceOf(Result.Err.class);
    }
}