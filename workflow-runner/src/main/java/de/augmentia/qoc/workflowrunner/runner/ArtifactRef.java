package de.augmentia.qoc.workflowrunner.runner;

import java.time.Instant;

/**
 * Immutable reference to externally stored workflow data.
 *
 * <p>Large payloads are kept in an {@link ArtifactStore}; prompts only receive
 * this compact reference (id, type, summary, uri) instead of the raw data.</p>
 */
public record ArtifactRef(
        String id,
        String type,
        String uri,
        String summary,
        long sizeBytes,
        Instant createdAt
) {
    public ArtifactRef {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (type == null || type.isBlank()) type = "data";
        if (uri == null) uri = "";
        if (summary == null) summary = "";
        if (sizeBytes < 0) sizeBytes = 0;
        if (createdAt == null) createdAt = Instant.now();
    }
}
