package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.InstructionStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory instruction store: state, entries and blobs per session.
 */
public final class InMemoryInstructionStore implements InstructionStore {

    private final RuntimeState state;

    private static final class InstructionData {
        InstructionState state;
        final List<InstructionEntry> entries = new ArrayList<>();
    }

    public InMemoryInstructionStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    private InstructionData data(SessionId session) {
        return (InstructionData) state.instructions.computeIfAbsent(session, k -> new InstructionData());
    }

    @Override
    public Option<InstructionState> state(SessionId session) {
        if (session == null) return Option.none();
        InstructionData d = (InstructionData) state.instructions.get(session);
        if (d == null || d.state == null) return Option.none();
        return Option.some(d.state);
    }

    @Override
    public void putState(SessionId session, InstructionState state) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        InstructionData d = data(session);
        ReentrantLock lock = this.state.lock(session);
        lock.lock();
        try {
            d.state = state;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<InstructionEntry> entries(SessionId session) {
        if (session == null) return List.of();
        InstructionData d = (InstructionData) state.instructions.get(session);
        if (d == null) return List.of();
        ReentrantLock lock = this.state.lock(session);
        lock.lock();
        try {
            return List.copyOf(d.entries);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void putEntry(SessionId session, InstructionEntry entry) {
        if (session == null || entry == null) throw new IllegalArgumentException("null argument");
        InstructionData d = data(session);
        ReentrantLock lock = this.state.lock(session);
        lock.lock();
        try {
            d.entries.removeIf(e -> e.key().equals(entry.key()));
            d.entries.add(entry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void removeEntry(SessionId session, String key) {
        if (session == null || key == null) return;
        InstructionData d = (InstructionData) state.instructions.get(session);
        if (d == null) return;
        ReentrantLock lock = this.state.lock(session);
        lock.lock();
        try {
            d.entries.removeIf(e -> e.key().equals(key));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Option<String> blob(String hash) {
        if (hash == null || hash.isBlank()) return Option.none();
        String v = state.instructionBlobs.get(hash);
        return v == null ? Option.none() : Option.some(v);
    }

    @Override
    public void putBlob(String hash, String value) {
        if (hash == null || hash.isBlank()) throw new IllegalArgumentException("hash must not be blank");
        state.instructionBlobs.put(hash, value);
    }
}
