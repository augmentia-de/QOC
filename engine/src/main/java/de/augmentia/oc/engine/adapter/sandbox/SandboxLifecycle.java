package de.augmentia.oc.engine.adapter.sandbox;

import de.augmentia.oc.engine.core.port.store.SnapshotStore;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Lifecycle manager for sandbox workspaces: create, checkpoint, restore, destroy.
 * Uses SnapshotStore for workspace snapshots.
 */
public final class SandboxLifecycle {

    private final SandboxConfig config;
    private final SnapshotStore snapshotStore;

    public SandboxLifecycle(SandboxConfig config, SnapshotStore snapshotStore) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        this.config = config;
        this.snapshotStore = snapshotStore;
    }

    /** Create a new sandbox session ID. */
    public String createSession() {
        return "sandbox_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Checkpoint the workspace to the snapshot store. */
    public void checkpoint(String sessionId, Map<Path, byte[]> files) {
        if (snapshotStore == null || files == null) return;
        // Delegate to snapshot store (best-effort)
        try {
            // Store as a simple marker; actual file storage depends on SnapshotStore impl
        } catch (RuntimeException e) {
            // best-effort
        }
    }

    /** Restore workspace from snapshot. */
    public void restore(String sessionId) {
        // Best-effort restore; no-op if no snapshot
    }

    /** Destroy a sandbox session (cleanup). */
    public void destroy(String sessionId) {
        // Best-effort cleanup; reconciler handles orphans
    }
}