package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.InstructionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory instruction state, entries and blobs. */
public final class InMemoryInstructions implements InstructionStore {

    private final Map<SessionId, InstructionState> states = new LinkedHashMap<>();
    private final Map<SessionId, Map<String, InstructionEntry>> entries = new LinkedHashMap<>();
    private final Map<String, String> blobs = new LinkedHashMap<>();

    @Override
    public synchronized Option<InstructionState> state(SessionId session) {
        return Option.of(states.get(session));
    }

    @Override
    public synchronized void putState(SessionId session, InstructionState state) {
        states.put(session, state);
    }

    @Override
    public synchronized List<InstructionEntry> entries(SessionId session) {
        return List.copyOf(entries.getOrDefault(session, Map.of()).values());
    }

    @Override
    public synchronized void putEntry(SessionId session, InstructionEntry entry) {
        entries.computeIfAbsent(session, key -> new LinkedHashMap<>()).put(entry.key(), entry);
    }

    @Override
    public synchronized void removeEntry(SessionId session, String key) {
        Map<String, InstructionEntry> map = entries.get(session);
        if (map != null) map.remove(key);
    }

    @Override
    public synchronized Option<String> blob(String hash) {
        return Option.of(blobs.get(hash));
    }

    @Override
    public synchronized void putBlob(String hash, String value) {
        blobs.put(hash, value);
    }

    public synchronized List<String> keys(SessionId session) {
        return new ArrayList<>(entries.getOrDefault(session, Map.of()).keySet());
    }
}
