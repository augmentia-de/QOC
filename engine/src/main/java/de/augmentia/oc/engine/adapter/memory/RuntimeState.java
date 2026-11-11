package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SnapshotId;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Container for all in-memory state. Shared across all InMemory adapters.
 *
 * <p>Thread-safety: {@link ConcurrentHashMap} for maps, {@link ReentrantLock}
 * per session for state changes. No I/O under lock (X-4).</p>
 */
public final class RuntimeState {

    public final ConcurrentHashMap<SessionId, Object> sessions = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<SessionId, java.util.Deque<de.augmentia.oc.engine.core.model.InboxEntry>> inbox = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<SessionId, Object> conversations = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, Object> journals = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<SessionId, Object> claims = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<SessionId, ReentrantLock> locks = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Object, Object> approvals = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<SessionId, Object> instructions = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, String> instructionBlobs = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<SessionId, Object> workspaces = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, Object> credentials = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<SnapshotId, Map<Path, byte[]>> snapshots = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, byte[]> toolOutputs = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Object, Object> toolCache = new ConcurrentHashMap<>();

    public final MemoryBudget budget;

    public RuntimeState(MemoryBudget budget) {
        this.budget = budget == null ? new MemoryBudget(1L << 30) : budget;
    }

    public ReentrantLock lock(SessionId id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }
}
