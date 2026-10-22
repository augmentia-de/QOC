package de.augmentia.oc.engine.core.event;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Payload;

public interface PayloadCodec {
    String type();

    int version();

    JsonValue encode(Payload payload);

    Payload decode(JsonValue value);
}