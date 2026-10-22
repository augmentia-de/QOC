package de.augmentia.oc.engine.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonParseErrorTest {

    @Test
    void unterminatedString() {
        assertThat(Json.parse("\"abc")).isInstanceOf(Result.Err.class);
    }

    @Test
    void badEscape() {
        assertThat(Json.parse("\"a\\q\"")).isInstanceOf(Result.Err.class);
    }

    @Test
    void badUnicode() {
        assertThat(Json.parse("\"\\u12\"")).isInstanceOf(Result.Err.class);
    }

    @Test
    void badNumber() {
        assertThat(Json.parse("1.2.3")).isInstanceOf(Result.Err.class);
    }

    @Test
    void missingColon() {
        assertThat(Json.parse("{\"a\" 1}")).isInstanceOf(Result.Err.class);
    }

    @Test
    void missingCommaOrBrace() {
        assertThat(Json.parse("{\"a\":1 \"b\":2}")).isInstanceOf(Result.Err.class);
    }

    @Test
    void missingArrayClose() {
        assertThat(Json.parse("[1,2")).isInstanceOf(Result.Err.class);
    }

    @Test
    void trailingCharsObject() {
        assertThat(Json.parse("{\"a\":1}x")).isInstanceOf(Result.Err.class);
    }

    @Test
    void emptyInput() {
        assertThat(Json.parse("   ")).isInstanceOf(Result.Err.class);
    }

    @Test
    void badLiteral() {
        assertThat(Json.parse("tru")).isInstanceOf(Result.Err.class);
        assertThat(Json.parse("nul")).isInstanceOf(Result.Err.class);
        assertThat(Json.parse("fals")).isInstanceOf(Result.Err.class);
    }

    @Test
    void decodeNullValue() {
        Result<JsonValue> r = Json.decode("null", Schema.str());
        assertThat(r).isInstanceOf(Result.Err.class);
    }

    @Test
    void decodeWrongType() {
        assertThat(Json.decode("true", Schema.str())).isInstanceOf(Result.Err.class);
        assertThat(Json.decode("\"x\"", Schema.intg())).isInstanceOf(Result.Err.class);
        assertThat(Json.decode("\"x\"", Schema.lng())).isInstanceOf(Result.Err.class);
        assertThat(Json.decode("\"x\"", Schema.bool())).isInstanceOf(Result.Err.class);
        assertThat(Json.decode("\"x\"", Schema.num())).isInstanceOf(Result.Err.class);
        assertThat(Json.decode("1", Schema.arr(Schema.intg()))).isInstanceOf(Result.Err.class);
        assertThat(Json.decode("1", Schema.obj(Map.of()))).isInstanceOf(Result.Err.class);
    }

    @Test
    void decodeEnumAndUnionNone() {
        assertThat(Json.decode("\"z\"", Schema.enm(List.of("a", "b")))).isInstanceOf(Result.Err.class);
        assertThat(Json.decode("true", Schema.union(List.of(Schema.str(), Schema.intg())))).isInstanceOf(Result.Err.class);
    }

    @Test
    void wildcardNull() {
        assertThat(Wildcard.match(null, "a")).isFalse();
        assertThat(Wildcard.match("a", null)).isFalse();
        assertThat(Wildcard.matches("a", "*")).isTrue();
    }

    @Test
    void wildcardWindowsFlagSmoke() {
        // just ensure no exception
        assertThat(Wildcard.match("GIT", "git")).isFalse(); // linux case-sensitive
    }
}