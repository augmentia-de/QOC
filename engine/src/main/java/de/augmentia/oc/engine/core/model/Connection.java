package de.augmentia.oc.engine.core.model;

public record Connection(String id, String integration, boolean active) {
    public Connection {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("connection id must not be blank");
        if (integration == null || integration.isBlank()) throw new IllegalArgumentException("integration must not be blank");
    }
}