package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactStoreTest {
    @Test
    void putAndGetRoundTrips() {
        InMemoryArtifactStore store = new InMemoryArtifactStore();
        ArtifactRef ref = new ArtifactRef("a1", "json", "store:a1", "summary", 10, Instant.now());
        store.put(ref, "payload");
        assertTrue(store.get("a1").isPresent());
        assertEquals("payload", store.get("a1").orElseThrow());
        assertEquals(1, store.list().size());
        assertTrue(store.remove("a1"));
        assertTrue(store.get("a1").isEmpty());
    }
}
