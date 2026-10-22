package de.augmentia.oc.engine.core.model;

import java.time.Instant;

public record StoredEvent(String aggregateId, Seq seq, String type, int version,
                          Payload payload, Instant time) {
    public StoredEvent {
        if (aggregateId == null || aggregateId.isBlank()) throw new IllegalArgumentException("aggregateId must not be blank");
        if (seq == null) throw new IllegalArgumentException("seq must not be null");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        if (time == null) time = Instant.EPOCH;
    }

    public static StoredEvent from(String aggregateId, Seq seq, EventDraft draft, Instant time) {
        return new StoredEvent(aggregateId, seq, draft.type(), draft.version(), draft.payload(), time);
    }
}