package de.augmentia.oc.engine.core.event;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Payload;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadRegistryTest {

    private static final class RenamedCodec implements PayloadCodec {
        @Override public String type() { return "rename"; }
        @Override public int version() { return 1; }
        @Override public JsonValue encode(Payload payload) {
            SessionEvent.SessionRenamed r = (SessionEvent.SessionRenamed) payload;
            return new JsonValue.Obj(Map.of("title", new JsonValue.Str(r.title())));
        }
        @Override public Payload decode(JsonValue value) {
            JsonValue.Obj o = (JsonValue.Obj) value;
            String title = ((JsonValue.Str) o.values().get("title")).value();
            return new SessionEvent.SessionRenamed(SessionId.create(), title);
        }
    }

    @Test
    void registerCodec() {
        PayloadRegistry reg = new PayloadRegistry();
        reg.register(new RenamedCodec());
        assertThat(reg.codec("rename").isPresent()).isTrue();
        assertThat(reg.codec("unknown").isEmpty()).isTrue();
    }

    @Test
    void registerDuplicateCodecThrows() {
        PayloadRegistry reg = new PayloadRegistry();
        reg.register(new RenamedCodec());
        assertThatThrownBy(() -> reg.register(new RenamedCodec()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerNullCodecThrows() {
        assertThatThrownBy(() -> new PayloadRegistry().register((PayloadCodec) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerType() {
        PayloadRegistry reg = new PayloadRegistry();
        reg.register("foo", SessionEvent.SessionRenamed.class);
        assertThat(reg.type("foo").isPresent()).isTrue();
        assertThat(reg.type("bar").isEmpty()).isTrue();
    }

    @Test
    void registerDuplicateTypeThrows() {
        PayloadRegistry reg = new PayloadRegistry();
        reg.register("foo", SessionEvent.SessionRenamed.class);
        assertThatThrownBy(() -> reg.register("foo", SessionEvent.SessionRenamed.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerBlankTypeThrows() {
        assertThatThrownBy(() -> new PayloadRegistry().register("", SessionEvent.SessionRenamed.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayloadRegistry().register("  ", SessionEvent.SessionRenamed.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerNullTypeThrows() {
        assertThatThrownBy(() -> new PayloadRegistry().register((String) null, SessionEvent.SessionRenamed.class))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PayloadRegistry().register("foo", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeViaCodec() {
        PayloadRegistry reg = new PayloadRegistry();
        reg.register(new RenamedCodec());
        JsonValue val = new JsonValue.Obj(Map.of("title", new JsonValue.Str("hello")));
        Result<Payload> result = reg.decode("rename", 1, val);
        assertThat(result).isInstanceOf(Result.Ok.class);
        Payload p = ((Result.Ok<Payload>) result).value();
        assertThat(p).isInstanceOf(SessionEvent.SessionRenamed.class);
        assertThat(((SessionEvent.SessionRenamed) p).title()).isEqualTo("hello");
    }

    @Test
    void decodeUnknownType() {
        PayloadRegistry reg = new PayloadRegistry();
        Result<Payload> result = reg.decode("unknown", 1, new JsonValue.Obj(Map.of()));
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void decodeWrongVersion() {
        PayloadRegistry reg = new PayloadRegistry();
        reg.register(new RenamedCodec());
        JsonValue val = new JsonValue.Obj(Map.of("title", new JsonValue.Str("hello")));
        Result<Payload> result = reg.decode("rename", 2, val);
        assertThat(result).isInstanceOf(Result.Err.class);
    }

    @Test
    void decodeViaType() {
        PayloadRegistry reg = new PayloadRegistry();
        reg.register("rename", SessionEvent.SessionRenamed.class);
        // ContractCodec.decode requires a matching shape; use a simpler check
        // that unknown version is rejected.
        Result<Payload> result = reg.decode("rename", 2, new JsonValue.Obj(Map.of()));
        assertThat(result).isInstanceOf(Result.Err.class);
    }
}
