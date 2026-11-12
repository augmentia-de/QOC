package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.SessionId;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Container for the minimal in-memory state used by the in-memory storage mode.
 *
 * <p>In-memory mode is intentionally minimal: only the state required for
 * direct execution (sessions, conversations, claims, workspaces, journal) is
 * kept. Snapshots, approvals, instructions, credentials, tool-output cache and
 * tool-result cache are served by no-op adapters and therefore need no state.</p>
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
    public final ConcurrentHashMap<SessionId, Object> workspaces = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, byte[]> toolOutputs = new ConcurrentHashMap<>();

    public RuntimeState() {}

    public ReentrantLock lock(SessionId id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }
}