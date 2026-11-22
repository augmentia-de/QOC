package de.augmentia.oc.engine.core.model;

import java.util.Objects;

/**
 * Reference to a context block that contributed to a provenance record.
 */
public record ContextBlockRef(String blockType, String id, String source) {
    public ContextBlockRef {
        Objects.requireNonNull(blockType, "blockType");
        if (id == null) id = "";
        if (source == null) source = "";
    }
}
