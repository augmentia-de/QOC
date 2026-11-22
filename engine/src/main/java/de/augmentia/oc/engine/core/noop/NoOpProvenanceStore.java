package de.augmentia.oc.engine.core.noop;

import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ProvenanceRecord;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ProvenanceStore;

import java.util.List;

public class NoOpProvenanceStore implements ProvenanceStore {
    public static final NoOpProvenanceStore INSTANCE = new NoOpProvenanceStore();
    private NoOpProvenanceStore() {}
    @Override public void append(SessionId sessionId, ProvenanceRecord record) {}
    @Override public List<ProvenanceRecord> byMessage(SessionId sessionId, MessageId assistantMessageId) { return List.of(); }
    @Override public List<ProvenanceRecord> bySession(SessionId sessionId, int limit) { return List.of(); }
}
