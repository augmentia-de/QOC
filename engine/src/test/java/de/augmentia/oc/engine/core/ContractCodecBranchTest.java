package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.event.ContractCodec;
import de.augmentia.oc.engine.core.event.EventCodec;
import de.augmentia.oc.engine.core.event.PayloadCodec;
import de.augmentia.oc.engine.core.event.PayloadRegistry;
import de.augmentia.oc.engine.core.json.DecodeError;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Payload;
import de.augmentia.oc.engine.core.model.SessionEvent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractCodecBranchTest {

    record Sample(boolean flag, int count, long total, double ratio, BigDecimal amount,
                  byte[] data, Instant at, Duration delay, Path path, Effect effect,
                  List<String> names, Map<String, Object> meta, Option<String> maybe) {}

    @Test
    void roundTripsPrimitiveAndCollectionFields() {
        Sample sample = new Sample(true, 3, 9L, 1.5, new BigDecimal("2.25"),
                new byte[]{1, 2, 3}, Instant.EPOCH, Duration.ofSeconds(30),
                Path.of("/tmp/x"), Effect.ALLOW, List.of("a", "b"),
                Map.of("k", "v"), Option.some("present"));

        Sample decoded = ContractCodec.decode(ContractCodec.encode(sample), Sample.class);

        assertThat(decoded.flag()).isTrue();
        assertThat(decoded.count()).isEqualTo(3);
        assertThat(decoded.total()).isEqualTo(9L);
        assertThat(decoded.ratio()).isEqualTo(1.5);
        assertThat(decoded.amount()).isEqualByComparingTo(new BigDecimal("2.25"));
        assertThat(decoded.data()).containsExactly(1, 2, 3);
        assertThat(decoded.at()).isEqualTo(Instant.EPOCH);
        assertThat(decoded.delay()).isEqualTo(Duration.ofSeconds(30));
        assertThat(decoded.path()).isEqualTo(Path.of("/tmp/x"));
        assertThat(decoded.effect()).isEqualTo(Effect.ALLOW);
        assertThat(decoded.names()).containsExactly("a", "b");
        assertThat(decoded.meta()).containsEntry("k", "v");
        assertThat(decoded.maybe().orElse("")).isEqualTo("present");
    }

    @Test
    void emptyOptionRoundTripsAsNone() {
        Sample sample = new Sample(false, 0, 0L, 0.0, BigDecimal.ZERO, new byte[0],
                Instant.EPOCH, Duration.ZERO, Path.of("."), Effect.DENY, List.of(), Map.of(), Option.none());

        Sample decoded = ContractCodec.decode(ContractCodec.encode(sample), Sample.class);

        assertThat(decoded.maybe().isEmpty()).isTrue();
        assertThat(decoded.names()).isEmpty();
    }

    @Test
    void payloadRegistryUsesRegisteredCodecs() {
        PayloadRegistry registry = new PayloadRegistry();
        PayloadCodec codec = new PayloadCodec() {
            @Override public String type() { return "ExecutionStarted"; }
            @Override public int version() { return 1; }
            @Override public JsonValue encode(Payload payload) { return JsonValue.of("custom"); }
            @Override public Payload decode(JsonValue value) {
                return new SessionEvent.ExecutionStarted(SessionIdFixture.id());
            }
        };
        registry.register(codec);

        assertThat(registry.codec("ExecutionStarted").isPresent()).isTrue();
        assertThat(registry.codec("Unknown").isEmpty()).isTrue();
        assertThat(registry.type("ExecutionStarted").isEmpty()).isTrue();

        Result<Payload> decoded = registry.decode("ExecutionStarted", 1, JsonValue.of("x"));
        assertThat(decoded).isInstanceOf(Result.Ok.class);
        assertThat(decoded.valueOrNull()).isInstanceOf(SessionEvent.ExecutionStarted.class);

        assertThat(registry.decode("ExecutionStarted", 2, JsonValue.of("x"))).isInstanceOf(Result.Err.class);
        assertThat(registry.decode("Unknown", 1, JsonValue.of("x"))).isInstanceOf(Result.Err.class);
        assertThatThrownBy(() -> registry.register(codec)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register("X", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payloadRegistryRejectsDuplicateTypes() {
        PayloadRegistry registry = new PayloadRegistry();
        registry.register("ExecutionStarted", SessionEvent.ExecutionStarted.class);
        assertThat(registry.type("ExecutionStarted").isPresent()).isTrue();
        assertThatThrownBy(() -> registry.register("ExecutionStarted", SessionEvent.ExecutionStarted.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeErrorCarriesPathAndMessage() {
        DecodeError error = new DecodeError("$.field", "missing");
        assertThat(error.path()).isEqualTo("$.field");
        assertThat(error.message()).isEqualTo("missing");
    }

    @Test
    void eventCodecUsesRegistryForUnknownPayloads() {
        EventCodec codec = new EventCodec(new PayloadRegistry());
        assertThatThrownBy(() -> codec.encode(new SessionEvent.ExecutionStarted(SessionIdFixture.id())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(codec.registry()).isNotNull();
    }

    static final class SessionIdFixture {
        static de.augmentia.oc.engine.core.model.SessionId id() {
            return de.augmentia.oc.engine.core.model.SessionId.create();
        }
    }
}
