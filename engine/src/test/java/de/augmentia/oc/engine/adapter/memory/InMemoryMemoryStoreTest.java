package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.AnswerabilityResult;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryDiff;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.port.store.MemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryMemoryStoreTest {

    @Test
    void upsertAndQuery() {
        MemoryStore store = new InMemoryMemoryStore();
        Fact f = new Fact("f1", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0);
        store.upsert(f);
        List<Fact> results = store.query(new MemoryQuery("Quarkus", null, null, null, 10));
        assertEquals(1, results.size());
        assertEquals("f1", results.get(0).factId());
    }

    @Test
    void retireMarksFact() {
        MemoryStore store = new InMemoryMemoryStore();
        Fact f = new Fact("f1", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0);
        store.upsert(f);
        store.retire("f1", "outdated", "f2");
        List<Fact> results = store.query(new MemoryQuery("Quarkus", "version", null, null, 10));
        // retired fact still present but validTo is now
        assertEquals(1, results.size());
        assertTrue(results.get(0).validTo().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void asOfReturnsHistorical() {
        MemoryStore store = new InMemoryMemoryStore();
        Instant past = Instant.now().minusSeconds(100);
        Fact f = new Fact("f1", "Quarkus", "version", "3.40", past, past, null, List.of(), 1.0, 0);
        store.upsert(f);
        var result = store.asOf("Quarkus", "version", past.plusSeconds(10));
        assertTrue(result.isPresent());
        assertEquals("3.40", result.get().value());
    }

    @Test
    void checkAnswerable() {
        MemoryStore store = new InMemoryMemoryStore();
        store.upsert(new Fact("f1", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0));
        AnswerabilityResult r = store.checkAnswerable("What is the Quarkus version?");
        assertTrue(r.answerable());
    }

    @Test
    void diffShowsAdded() {
        MemoryStore store = new InMemoryMemoryStore();
        Instant before = Instant.now().minusSeconds(10);
        store.upsert(new Fact("f1", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0));
        MemoryDiff diff = store.diff(before);
        assertEquals(1, diff.added().size());
    }

    @Test
    void queryByText() {
        MemoryStore store = new InMemoryMemoryStore();
        store.upsert(new Fact("f1", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0));
        store.upsert(new Fact("f2", "Java", "version", "21", Instant.now(), null, null, List.of(), 1.0, 0));
        List<Fact> results = store.query(new MemoryQuery(null, null, null, "Quarkus", 10));
        assertEquals(1, results.size());
        assertEquals("Quarkus", results.get(0).entity());
    }

    @Test
    void queryLimit() {
        MemoryStore store = new InMemoryMemoryStore();
        for (int i = 0; i < 5; i++) {
            store.upsert(new Fact("f" + i, "E", "r", "v" + i, Instant.now(), null, null, List.of(), 1.0, 0));
        }
        List<Fact> results = store.query(new MemoryQuery("E", null, null, null, 3));
        assertEquals(3, results.size());
    }

    @Test
    void noOpStore() {
        MemoryStore store = de.augmentia.oc.engine.core.noop.NoOpMemoryStore.INSTANCE;
        store.upsert(new Fact("f1", "E", "r", "v", Instant.now(), null, null, List.of(), 1.0, 0));
        assertTrue(store.query(new MemoryQuery("E", null, null, null, 10)).isEmpty());
        assertTrue(store.asOf("E", "r", Instant.now()).isEmpty());
    }
}