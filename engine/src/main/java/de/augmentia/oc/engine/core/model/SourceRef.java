package de.augmentia.oc.engine.core.model;

import java.util.Objects;

/**
 * Reference to an external source (file, URL, document) contributing to an answer.
 */
public record SourceRef(String uri, String hash, String mimeType) {
    public SourceRef {
        Objects.requireNonNull(uri, "uri");
        if (hash == null) hash = "";
        if (mimeType == null) mimeType = "";
    }
}
