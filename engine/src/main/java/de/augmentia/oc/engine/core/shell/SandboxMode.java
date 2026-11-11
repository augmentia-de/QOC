package de.augmentia.oc.engine.core.shell;

/**
 * OS-level confinement mode for shell/process execution. Interpreted by the
 * adapter layer (e.g. {@code SandboxedProcessRunner}); {@code engine.core}
 * stays free of OS specifics.
 */
public enum SandboxMode {
    /** No confinement; delegate to the inner runner (backward compatible). */
    NONE,
    /** Read-only filesystem: only /dev, /proc and the workspace (read) are visible. */
    READ_ONLY,
    /** Workspace-write: workspace + /tmp writable, rest read-only. */
    WORKSPACE_WRITE,
    /** No confinement at all (explicit opt-out). */
    DANGER_FULL_ACCESS;

    public static SandboxMode fromString(String value) {
        if (value == null) return NONE;
        String v = value.trim().toLowerCase().replace("-", "_");
        return switch (v) {
            case "none" -> NONE;
            case "read_only", "readonly", "ro" -> READ_ONLY;
            case "workspace_write", "workspace-write", "workspace", "ww" -> WORKSPACE_WRITE;
            case "danger_full_access", "danger-full-access", "danger", "off" -> DANGER_FULL_ACCESS;
            default -> NONE;
        };
    }
}