package de.augmentia.oc.engine.core.model;

public record FileDiff(String path, String oldContent, String newContent, Kind kind) {
    public enum Kind {
        ADDED, MODIFIED, DELETED
    }

    public FileDiff {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path must not be blank");
        if (oldContent == null) oldContent = "";
        if (newContent == null) newContent = "";
        if (kind == null) kind = Kind.MODIFIED;
    }
}