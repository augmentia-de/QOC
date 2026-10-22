package de.augmentia.oc.engine.core.model;

import java.time.Instant;

public record AssistantTime(Instant created, Instant completed) {
    public AssistantTime {
        if (created == null) created = Instant.EPOCH;
    }

    public static AssistantTime started(Instant created) {
        return new AssistantTime(created, null);
    }

    public AssistantTime completed(Instant at) {
        return new AssistantTime(created, at);
    }
}