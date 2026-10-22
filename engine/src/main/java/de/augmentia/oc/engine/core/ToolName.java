package de.augmentia.oc.engine.core;

/**
 * Opaque tool name. MCP tools use namespaced forms; validation is intentionally
 * permissive (non-empty, no whitespace-only).
 */
public final class ToolName {

    private final String value;

    public ToolName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        this.value = value;
    }

    public static ToolName of(String value) {
        return new ToolName(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ToolName t && t.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
