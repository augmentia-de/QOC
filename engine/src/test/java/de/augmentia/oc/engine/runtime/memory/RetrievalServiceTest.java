package de.augmentia.oc.engine.runtime.memory;

import de.augmentia.oc.engine.adapter.memory.InMemoryMemoryStore;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.port.store.MemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalServiceTest {

    @Test
    void retrieveReturnsRelevantFacts() {
        MemoryStore store = new InMemoryMemoryStore();
        store.upsert(new Fact("f1", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0));
        store.upsert(new Fact("f2", "Java", "version", "21", Instant.now(), null, null, List.of(), 1.0, 0));
        List<Fact> results = RetrievalService.retrieve(store, "Quarkus version", 5);
        assertFalse(results.isEmpty());
        assertEquals("Quarkus", results.get(0).entity());
    }

    @Test
    void retrieveHandlesEmptyStore() {
        MemoryStore store = new InMemoryMemoryStore();
        List<Fact> results = RetrievalService.retrieve(store, "anything", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveHandlesBlankQuery() {
        MemoryStore store = new InMemoryMemoryStore();
        store.upsert(new Fact("f1", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0));
        List<Fact> results = RetrievalService.retrieve(store, "", 5);
        // Should return facts (fallback)
        assertFalse(results.isEmpty());
    }

    @Test
    void retrieveRespectsTopK() {
        MemoryStore store = new InMemoryMemoryStore();
        for (int i = 0; i < 10; i++) {
            store.upsert(new Fact("f" + i, "Entity" + i, "rel", "val" + i, Instant.now(), null, null, List.of(), 1.0, 0));
        }
        List<Fact> results = RetrievalService.retrieve(store, "Entity", 3);
        assertEquals(3, results.size());
    }
}