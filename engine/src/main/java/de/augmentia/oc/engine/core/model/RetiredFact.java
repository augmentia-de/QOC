package de.augmentia.oc.engine.core.model;

import java.time.Instant;
import java.util.Objects;

public record RetiredFact(String factId, Instant retiredAt, String reason, String supersededBy) {
    public RetiredFact {
        Objects.requireNonNull(factId, "factId");
        if (retiredAt == null) retiredAt = Instant.now();
        if (reason == null) reason = "";
        if (supersededBy == null) supersededBy = "";
    }
}
