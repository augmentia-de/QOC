package de.augmentia.oc.engine.core.model;

public record EventDraft(String aggregateId, int version, Payload payload) {
    public EventDraft {
        if (aggregateId == null || aggregateId.isBlank()) throw new IllegalArgumentException("aggregateId must not be blank");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
    }

    public String type() {
        return payload.getClass().getSimpleName();
    }

    public static EventDraft of(String aggregateId, Payload payload) {
        return new EventDraft(aggregateId, 1, payload);
    }
}