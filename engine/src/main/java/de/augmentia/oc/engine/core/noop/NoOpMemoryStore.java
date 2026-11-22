package de.augmentia.oc.engine.core.noop;

import de.augmentia.oc.engine.core.model.AnswerabilityResult;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryDiff;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.port.store.MemoryStore;

import java.time.Instant;
import java.util.List;

public class NoOpMemoryStore implements MemoryStore {
    public static final NoOpMemoryStore INSTANCE = new NoOpMemoryStore();
    private NoOpMemoryStore() {}
    @Override public void upsert(Fact fact) {}
    @Override public void retire(String factId, String reason, String supersededBy) {}
    @Override public List<Fact> query(MemoryQuery query) { return List.of(); }
    @Override public java.util.Optional<Fact> asOf(String entity, String relation, Instant instant) { return java.util.Optional.empty(); }
    @Override public MemoryDiff diff(Instant since) { return new MemoryDiff(since, List.of(), List.of(), List.of()); }
    @Override public AnswerabilityResult checkAnswerable(String question) { return new AnswerabilityResult(true, List.of(), List.of(), ""); }
}
