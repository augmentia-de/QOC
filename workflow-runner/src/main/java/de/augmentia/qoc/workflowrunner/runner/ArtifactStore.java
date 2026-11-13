package de.augmentia.qoc.workflowrunner.runner;

import java.util.List;
import java.util.Optional;

/**
 * Storage for workflow artifacts. Prompts receive {@link ArtifactRef}s, not
 * the payloads.
 */
public interface ArtifactStore {
    String put(ArtifactRef ref, Object payload);
    Optional<Object> get(String id);
    List<ArtifactRef> list();
    boolean remove(String id);
}
