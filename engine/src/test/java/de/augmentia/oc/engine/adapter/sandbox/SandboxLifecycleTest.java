package de.augmentia.oc.engine.adapter.sandbox;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SandboxLifecycleTest {

    @Test
    void createSessionReturnsId() {
        SandboxConfig config = new SandboxConfig("local", null, null, 0, 0, 0, null, null);
        SandboxLifecycle lc = new SandboxLifecycle(config, null);
        String id = lc.createSession();
        assertNotNull(id);
        assertTrue(id.startsWith("sandbox_"));
    }

    @Test
    void checkpointDoesNotThrow() {
        SandboxConfig config = new SandboxConfig("local", null, null, 0, 0, 0, null, null);
        SandboxLifecycle lc = new SandboxLifecycle(config, null);
        assertDoesNotThrow(() -> lc.checkpoint("s1", Map.of()));
    }

    @Test
    void destroyDoesNotThrow() {
        SandboxConfig config = new SandboxConfig("local", null, null, 0, 0, 0, null, null);
        SandboxLifecycle lc = new SandboxLifecycle(config, null);
        assertDoesNotThrow(() -> lc.destroy("s1"));
    }
}