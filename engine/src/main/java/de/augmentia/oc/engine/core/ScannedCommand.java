package de.augmentia.oc.engine.core;

public record ScannedCommand(String resource, String save) {
    public ScannedCommand {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("resource must not be blank");
        }
        if (save == null || save.isBlank()) {
            throw new IllegalArgumentException("save must not be blank");
        }
    }
}