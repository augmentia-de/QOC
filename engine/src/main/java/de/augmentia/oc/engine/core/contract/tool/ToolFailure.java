package de.augmentia.oc.engine.core.contract.tool;

/** Channel 1 at the tool boundary: expected, model-facing failure. */
public final class ToolFailure extends RuntimeException {
    public ToolFailure(String message) {
        super(message);
    }

    public ToolFailure(String message, Throwable cause) {
        super(message, cause);
    }
}