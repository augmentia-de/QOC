package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ProvenanceRecord;
import de.augmentia.oc.engine.core.model.SessionId;

import java.util.List;
import java.util.Optional;

/**
 * Optional port for provenance records. Null-safe: a null instance means
 * provenance is disabled.
 */
public interface ProvenanceStore {
    void append(SessionId sessionId, ProvenanceRecord record);

    List<ProvenanceRecord> byMessage(SessionId sessionId, MessageId assistantMessageId);

    List<ProvenanceRecord> bySession(SessionId sessionId, int limit);

    default Optional<ProvenanceRecord> latest(SessionId sessionId) {
        List<ProvenanceRecord> all = bySession(sessionId, 1);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }
}
