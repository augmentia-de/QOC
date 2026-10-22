package de.augmentia.oc.engine.core.error;

/** Channel 2: deliberate stop. Never model-facing, never converted into an error. */
public final class Aborted extends RuntimeException {
    public Aborted(String reason) {
        super(reason);
    }
}