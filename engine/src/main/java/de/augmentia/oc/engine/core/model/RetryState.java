package de.augmentia.oc.engine.core.model;

import java.time.Instant;

public record RetryState(int attempt, Instant at, SessionError error) {
    public RetryState {
        if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
        if (at == null) at = Instant.EPOCH;
        if (error == null) throw new IllegalArgumentException("error must not be null");
    }
}