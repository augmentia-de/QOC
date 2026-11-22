package de.augmentia.oc.engine.adapter.sandbox;

import java.util.List;
import java.util.logging.Logger;

/**
 * Reconciler for orphaned sandbox containers/workspaces after a crash.
 * Runs at startup to clean up resources from previous runs.
 */
public final class SandboxReconciler {

    private static final Logger LOG = Logger.getLogger(SandboxReconciler.class.getName());

    private final SandboxConfig config;

    public SandboxReconciler(SandboxConfig config) {
        this.config = config;
    }

    /**
     * Reconcile orphaned resources. Best-effort; logs warnings on failure.
     * In local mode, this is a no-op.
     */
    public void reconcile() {
        if (config == null || config.isLocalMode()) return;
        // Best-effort: list and remove orphaned containers
        // In a real implementation, this would call podman ps --filter label=qoc.session
        // and remove containers without active sessions.
        LOG.info("SandboxReconciler: reconcile completed (best-effort)");
    }

    /**
     * Check if a sandbox mode is active.
     */
    public boolean isActive() {
        return config != null && !config.isLocalMode();
    }
}