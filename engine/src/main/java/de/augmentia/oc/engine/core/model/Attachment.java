package de.augmentia.oc.engine.core.model;

public record Attachment(String path, String mime) {
    public Attachment {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("attachment path must not be blank");
        if (mime == null) mime = "application/octet-stream";
    }
}