package de.augmentia.oc.engine.core.model;

import java.time.Instant;

public record InboxEntry(InboxId id, SessionId sessionId, InboxItem item, Delivery delivery,
                         Seq enqueuedSeq, Instant created) {
    public InboxEntry {
        if (id == null || sessionId == null || item == null || delivery == null || enqueuedSeq == null) {
            throw new IllegalArgumentException("inbox entry fields must not be null");
        }
        if (created == null) created = Instant.EPOCH;
    }
}