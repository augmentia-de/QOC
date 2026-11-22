package de.augmentia.oc.engine.runtime.memory;

import de.augmentia.oc.engine.adapter.memory.InMemoryMemoryStore;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.port.store.MemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionServiceTest {

    @Test
    void extractsDeterministicFacts() {
        String text = "Quarkus is a Java framework. Java is version 21.";
        List<Fact> facts = ExtractionService.extractDeterministic(text, "test");
        assertFalse(facts.isEmpty());
        assertTrue(facts.stream().anyMatch(f -> f.entity().equalsIgnoreCase("Quarkus")));
    }

    @Test
    void returnsEmptyForBlank() {
        assertTrue(ExtractionService.extractDeterministic("", "test").isEmpty());
        assertTrue(ExtractionService.extractDeterministic(null, "test").isEmpty());
    }

    @Test
    void extractAndStoreRespectsMaxTier() {
        MemoryStore store = new InMemoryMemoryStore();
        int count = ExtractionService.extractAndStore(store, "Quarkus is fast.", "test", 0);
        assertEquals(0, count); // maxTier=0 means manual only
    }

    @Test
    void extractAndStoreWithMaxTier1() {
        MemoryStore store = new InMemoryMemoryStore();
        int count = ExtractionService.extractAndStore(store, "Quarkus is fast.", "test", 1);
        assertTrue(count >= 0);
    }
}