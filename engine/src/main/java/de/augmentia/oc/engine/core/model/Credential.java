package de.augmentia.oc.engine.core.model;

import java.time.Instant;

public record Credential(String id, String integration, String kind, Option<String> secret,
                         boolean active, Instant created) {
    public Credential {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("credential id must not be blank");
        if (integration == null || integration.isBlank()) throw new IllegalArgumentException("integration must not be blank");
        if (kind == null) kind = "api-key";
        if (secret == null) secret = Option.none();
        if (created == null) created = Instant.EPOCH;
    }
}