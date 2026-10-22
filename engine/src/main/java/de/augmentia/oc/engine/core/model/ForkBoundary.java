package de.augmentia.oc.engine.core.model;

public record ForkBoundary(MessageId messageId) {
    public ForkBoundary {
        if (messageId == null) throw new IllegalArgumentException("messageId must not be null");
    }
}