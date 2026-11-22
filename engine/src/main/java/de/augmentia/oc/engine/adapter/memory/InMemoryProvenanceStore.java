package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ProvenanceRecord;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ProvenanceStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryProvenanceStore implements ProvenanceStore {
    private final Map<SessionId, List<ProvenanceRecord>> data = new ConcurrentHashMap<>();

    @Override
    public void append(SessionId sessionId, ProvenanceRecord record) {
        data.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(record);
    }

    @Override
    public List<ProvenanceRecord> byMessage(SessionId sessionId, MessageId assistantMessageId) {
        List<ProvenanceRecord> out = new ArrayList<>();
        for (ProvenanceRecord r : data.getOrDefault(sessionId, List.of())) {
            if (r.assistantMessageId().equals(assistantMessageId)) out.add(r);
        }
        return List.copyOf(out);
    }

    @Override
    public List<ProvenanceRecord> bySession(SessionId sessionId, int limit) {
        List<ProvenanceRecord> all = data.getOrDefault(sessionId, List.of());
        int n = Math.min(limit, all.size());
        return List.copyOf(all.subList(Math.max(0, all.size() - n), all.size()));
    }
}
