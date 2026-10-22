package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.json.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {

    @Test
    void roundTripPrimitive() {
        JsonValue v = JsonValue.of("hello");
        String encoded = Json.encode(v);
        Result<JsonValue> decoded = Json.decode(encoded, Schema.str());
        assertThat(decoded).isInstanceOf(Result.Ok.class);
        assertThat(decoded.valueOrNull()).isEqualTo(v);
        assertThat(Json.encode(decoded.valueOrNull())).isEqualTo(encoded);
    }

    @Test
    void roundTripObjectAndArray() {
        JsonValue obj = JsonValue.of(Map.of(
                "name", JsonValue.of("x"),
                "count", JsonValue.of(3L),
                "items", JsonValue.of(List.of(JsonValue.of(true), JsonValue.ofNull()))
        ));
        String encoded = Json.encode(obj);
        Schema schema = Schema.obj(Map.of(
                "name", Schema.str(),
                "count", Schema.lng(),
                "items", Schema.arr(Schema.union(List.of(Schema.bool(), Schema.any())))
        ));
        Result<JsonValue> decoded = Json.decode(encoded, schema);
        assertThat(decoded).isInstanceOf(Result.Ok.class);
        assertThat(Json.encode(decoded.valueOrNull())).isEqualTo(encoded);
    }

    @Test
    void decodeRejectsMissingField() {
        Result<JsonValue> r = Json.decode("{\"a\":1}", Schema.obj(Map.of("a", Schema.intg(), "b", Schema.str())));
        assertThat(r).isInstanceOf(Result.Err.class);
    }

    @Test
    void decodeRejectsUnknownField() {
        Result<JsonValue> r = Json.decode("{\"a\":1,\"x\":2}", Schema.obj(Map.of("a", Schema.intg())));
        assertThat(r).isInstanceOf(Result.Err.class);
    }

    @Test
    void decodeNumberScale() {
        Result<JsonValue> r = Json.decode("3.14", Schema.num());
        assertThat(r).isInstanceOf(Result.Ok.class);
        assertThat(((JsonValue.Num) r.valueOrNull()).value()).isEqualByComparingTo(new BigDecimal("3.14"));
    }
}