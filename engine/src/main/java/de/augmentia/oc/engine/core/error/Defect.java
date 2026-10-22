package de.augmentia.oc.engine.core.error;

/** Channel 3: invariant violation. Never converted into an error. */
public final class Defect extends RuntimeException {
    public Defect(String message) {
        super(message);
    }

    public Defect(String message, Throwable cause) {
        super(message, cause);
    }
}