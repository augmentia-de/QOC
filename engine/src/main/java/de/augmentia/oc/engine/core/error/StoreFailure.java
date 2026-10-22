package de.augmentia.oc.engine.core.error;

public final class StoreFailure extends RuntimeException {
    public StoreFailure(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreFailure(String message) {
        super(message);
    }
}