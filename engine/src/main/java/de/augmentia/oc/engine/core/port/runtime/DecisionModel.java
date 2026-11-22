package de.augmentia.oc.engine.core.port.runtime;

import de.augmentia.oc.engine.core.model.DecisionRequest;
import de.augmentia.oc.engine.core.model.DecisionResult;

/**
 * Optional port for a specialized decision model (e.g. TypeSafe System One,
 * Flide). Null-safe: a null instance means decision support is disabled and
 * callers fall back to their default behaviour.
 */
public interface DecisionModel {
    DecisionResult decide(DecisionRequest request);

    default boolean isAvailable() {
        return true;
    }
}
