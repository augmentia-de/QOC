package de.augmentia.oc.engine.core.model;

import java.util.List;

public record SnapshotRange(String from, String to, List<String> files) {
    public SnapshotRange {
        if (from == null || to == null) throw new IllegalArgumentException("snapshot bounds must not be null");
        files = files == null ? List.of() : List.copyOf(files);
    }
}