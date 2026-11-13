package de.augmentia.oc.engine.core;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers numeric bounds, optional schemas and string escaping of the JSON codec. */
class JsonCodecCoverageTest {

    @Test
    void encodeNullRendersLiteralNull() {
        assertThat(Json.encode(null)).isEqualTo("null");
    }

    @Test
    void decodePropagatesParseErrors() {
        assertThat(Json.decode("not-json", Schema.any())).isInstanceOf(Result.Err.class);
    }

    @Test
    void intAndLongOverflowAreRejected() {
        assertThat(Json.decode(JsonValue.of(new BigDecimal("99999999999999999999")), Schema.integer()))
                .isInstanceOf(Result.Err.class);
        assertThat(Json.decode(JsonValue.of(new BigDecimal("999999999999999999999999999999")), Schema.longg()))
                .isInstanceOf(Result.Err.class);
    }

    @Test
    void optionalObjectFieldWithWrongTypeIsRejected() {
        Result<JsonValue> result = Json.decode("{\"a\":5}",
                Schema.obj(Map.of("a", Schema.opt(Schema.str()))));
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void topLevelOptionalAcceptsNullAndValidValues() {
        assertThat(Json.decode(JsonValue.ofNull(), Schema.opt(Schema.str()))).isInstanceOf(Result.Ok.class);
        assertThat(Json.decode(JsonValue.of("x"), Schema.opt(Schema.str()))).isInstanceOf(Result.Ok.class);
        assertThat(Json.decode(JsonValue.of(5), Schema.opt(Schema.str()))).isInstanceOf(Result.Err.class);
    }

    @Test
    void encodeEscapesAllSpecialCharacters() {
        String encoded = Json.encode(JsonValue.of("\"\\\n\r\t\b\f\u0001"));
        assertThat(encoded).isEqualTo("\"\\\"\\\\\\n\\r\\t\\b\\f\\u0001\"");
    }

    @Test
    void parseDecodesAllShortEscapes() {
        JsonValue value = Json.parse("\"\\\"\\\\\\/\\n\\r\\t\\b\\f\\u0041\"").valueOrNull();
        assertThat(((JsonValue.Str) value).value()).isEqualTo("\"\\/\n\r\t\b\fA");
    }

    @Test
    void parseRejectsBadEscapeAndTruncatedUnicode() {
        assertThat(Json.parse("\"\\x\"")).isInstanceOf(Result.Err.class);
        assertThat(Json.parse("\"\\u12\"")).isInstanceOf(Result.Err.class);
        assertThat(Json.parse("\"abc")).isInstanceOf(Result.Err.class);
    }
}
