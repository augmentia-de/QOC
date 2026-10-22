package de.augmentia.oc.engine.core.model;

import java.time.Instant;

public record Claim(SessionId sessionId, Instant claimedAt, int resumeAttempts) {
    public Claim {
        if (sessionId == null) throw new IllegalArgumentException("sessionId must not be null");
        if (claimedAt == null) claimedAt = Instant.EPOCH;
        if (resumeAttempts < 0) throw new IllegalArgumentException("resumeAttempts must not be negative");
    }
}