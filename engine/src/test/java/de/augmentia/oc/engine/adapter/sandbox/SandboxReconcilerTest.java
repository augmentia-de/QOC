package de.augmentia.oc.engine.adapter.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SandboxReconcilerTest {

    @Test
    void localModeNotActive() {
        SandboxConfig config = new SandboxConfig("local", null, null, 0, 0, 0, null, null);
        SandboxReconciler reconciler = new SandboxReconciler(config);
        assertFalse(reconciler.isActive());
    }

    @Test
    void containerModeActive() {
        SandboxConfig config = new SandboxConfig("container", null, null, 0, 0, 0, null, null);
        SandboxReconciler reconciler = new SandboxReconciler(config);
        assertTrue(reconciler.isActive());
    }

    @Test
    void reconcileDoesNotThrow() {
        SandboxConfig config = new SandboxConfig("local", null, null, 0, 0, 0, null, null);
        SandboxReconciler reconciler = new SandboxReconciler(config);
        assertDoesNotThrow(() -> reconciler.reconcile());
    }

    @Test
    void nullConfigNotActive() {
        SandboxReconciler reconciler = new SandboxReconciler(null);
        assertFalse(reconciler.isActive());
    }
}