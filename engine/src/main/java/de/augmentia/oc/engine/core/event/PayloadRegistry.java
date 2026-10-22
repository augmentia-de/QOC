package de.augmentia.oc.engine.core.event;

import de.augmentia.oc.engine.core.json.DecodeError;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Payload;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PayloadRegistry {

    private final Map<String, PayloadCodec> codecs = new LinkedHashMap<>();
    private final Map<String, Class<? extends Payload>> types = new LinkedHashMap<>();

    public PayloadRegistry register(PayloadCodec codec) {
        if (codec == null) throw new IllegalArgumentException("codec must not be null");
        if (codecs.putIfAbsent(codec.type(), codec) != null) {
            throw new IllegalArgumentException("duplicate payload codec: " + codec.type());
        }
        return this;
    }

    public PayloadRegistry register(String type, Class<? extends Payload> payloadType) {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        if (payloadType == null) throw new IllegalArgumentException("payloadType must not be null");
        if (types.putIfAbsent(type, payloadType) != null) {
            throw new IllegalArgumentException("duplicate payload type: " + type);
        }
        return this;
    }

    public Option<PayloadCodec> codec(String type) {
        return Option.of(codecs.get(type));
    }

    public Option<Class<? extends Payload>> type(String type) {
        return Option.of(types.get(type));
    }

    public Result<Payload> decode(String type, int version, JsonValue value) {
        PayloadCodec codec = codecs.get(type);
        if (codec != null) {
            if (version != codec.version()) {
                return new Result.Err<>(new DecodeError("$", "unsupported version " + version + " for " + type));
            }
            try {
                return new Result.Ok<>(codec.decode(value));
            } catch (RuntimeException e) {
                return new Result.Err<>(new DecodeError("$", e.getMessage()));
            }
        }

        Class<? extends Payload> payloadType = types.get(type);
        if (payloadType == null) {
            return new Result.Err<>(new DecodeError("$", "unknown payload type: " + type));
        }
        if (version != 1) {
            return new Result.Err<>(new DecodeError("$", "unsupported version " + version + " for " + type));
        }
        try {
            return new Result.Ok<>(ContractCodec.decode(value, payloadType));
        } catch (RuntimeException e) {
            return new Result.Err<>(new DecodeError("$", e.getMessage()));
        }
    }
}
