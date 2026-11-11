package de.augmentia.oc.engine.adapter.memory;

/**
 * Typed error when a memory budget is exceeded.
 */
public final class MemoryLimitExceeded extends RuntimeException {
    public MemoryLimitExceeded(String message) {
        super(message);
    }
}
