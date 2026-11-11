package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.port.store.SessionReader;
import de.augmentia.oc.engine.core.port.store.SessionStore;
import de.augmentia.oc.engine.core.port.store.SessionWorkspaceStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory session store: holds {@link SessionInfo} and inbox entries in
 * {@link ConcurrentHashMap}s keyed by {@link SessionId}.
 */
public final class InMemorySessionStore implements SessionStore, SessionWorkspaceStore {

    private final RuntimeState state;

    public InMemorySessionStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    // --- SessionReader ---

    @Override
    public Option<SessionInfo> info(SessionId session) {
        if (session == null) return Option.none();
        SessionInfo info = (SessionInfo) state.sessions.get(session);
        return info == null ? Option.none() : Option.some(info);
    }

    @Override
    public List<InboxEntry> inbox(SessionId session) {
        if (session == null) return List.of();
        java.util.Deque<InboxEntry> q = state.inbox.get(session);
        if (q == null) return List.of();
        List<InboxEntry> result = new ArrayList<>();
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            for (InboxEntry e : q) result.add(e);
        } finally {
            lock.unlock();
        }
        return List.copyOf(result);
    }

    @Override
    public Option<InboxEntry> inboxItem(SessionId session, InboxId id) {
        if (session == null || id == null) return Option.none();
        java.util.Deque<InboxEntry> q = state.inbox.get(session);
        if (q == null) return Option.none();
        for (InboxEntry e : q) {
            if (e.id().equals(id)) return Option.some(e);
        }
        return Option.none();
    }

    // --- SessionStore ---

    @Override
    public void put(SessionInfo info) {
        if (info == null) throw new IllegalArgumentException("info must not be null");
        ReentrantLock lock = state.lock(info.id());
        lock.lock();
        try {
            state.sessions.put(info.id(), info);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setIdleOutcome(SessionId id, String outcome) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        ReentrantLock lock = state.lock(id);
        lock.lock();
        try {
            SessionInfo info = (SessionInfo) state.sessions.get(id);
            if (info != null) {
                // SessionInfo is immutable; update via put with modified info
                // For simplicity, store outcome in workspace state
                Object ws = state.workspaces.get(id);
                if (ws instanceof WorkspaceState) {
                    ((WorkspaceState) ws).idleOutcome = outcome;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void putInbox(InboxEntry entry) {
        if (entry == null) throw new IllegalArgumentException("entry must not be null");
        ReentrantLock lock = state.lock(entry.sessionId());
        lock.lock();
        try {
            state.inbox.computeIfAbsent(entry.sessionId(), k -> new ConcurrentLinkedDeque<>()).addLast(entry);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void removeInbox(SessionId session, InboxId id) {
        if (session == null || id == null) return;
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            java.util.Deque<InboxEntry> q = state.inbox.get(session);
            if (q != null) q.removeIf(e -> e.id().equals(id));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<SessionInfo> list() {
        List<SessionInfo> result = new ArrayList<>();
        for (Object o : state.sessions.values()) {
            if (o instanceof SessionInfo) result.add((SessionInfo) o);
        }
        return List.copyOf(result);
    }

    @Override
    public boolean delete(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            state.sessions.remove(session);
            state.inbox.remove(session);
            state.conversations.remove(session);
            state.claims.remove(session);
            state.workspaces.remove(session);
            state.instructions.remove(session);
            state.locks.remove(session);
            return true;
        } finally {
            lock.unlock();
        }
    }

    // --- SessionWorkspaceStore ---

    @Override
    public void put(SessionId id, String executionMode, String stateStr, String workspaceRoot,
                    String instanceId, boolean retain) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        ReentrantLock lock = state.lock(id);
        lock.lock();
        try {
            state.workspaces.put(id, new WorkspaceState(executionMode, stateStr, workspaceRoot, instanceId, retain));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String executionMode(SessionId id) {
        WorkspaceState ws = (WorkspaceState) state.workspaces.get(id);
        return ws == null ? "workspace" : ws.executionMode;
    }

    @Override
    public String state(SessionId id) {
        WorkspaceState ws = (WorkspaceState) state.workspaces.get(id);
        return ws == null ? "active" : ws.state;
    }

    @Override
    public String workspaceRoot(SessionId id) {
        WorkspaceState ws = (WorkspaceState) state.workspaces.get(id);
        return ws == null ? null : ws.workspaceRoot;
    }

    @Override
    public boolean retain(SessionId id) {
        WorkspaceState ws = (WorkspaceState) state.workspaces.get(id);
        return ws != null && ws.retain;
    }

    @Override
    public void setPausedPrompt(SessionId id, String prompt) {
        WorkspaceState ws = (WorkspaceState) state.workspaces.get(id);
        if (ws != null) ws.pausedPrompt = prompt;
    }

    @Override
    public String pausedPrompt(SessionId id) {
        WorkspaceState ws = (WorkspaceState) state.workspaces.get(id);
        return ws == null ? null : ws.pausedPrompt;
    }

    @Override
    public String instanceId(SessionId id) {
        WorkspaceState ws = (WorkspaceState) state.workspaces.get(id);
        return ws == null ? null : ws.instanceId;
    }

    @Override
    public void updateState(SessionId id, String stateStr) {
        WorkspaceState ws = (WorkspaceState) state.workspaces.get(id);
        if (ws != null) ws.state = stateStr;
    }

    @Override
    public List<SessionId> ephemeralOrphans(String instanceId) {
        List<SessionId> result = new ArrayList<>();
        for (var entry : state.workspaces.entrySet()) {
            WorkspaceState ws = (WorkspaceState) entry.getValue();
            if (ws != null && "ephemeral".equals(ws.executionMode)
                    && ("active".equals(ws.state) || "cleanup_pending".equals(ws.state))
                    && (ws.instanceId == null || ws.instanceId.equals(instanceId))) {
                result.add(entry.getKey());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void deleteWorkspace(SessionId id) {
        state.workspaces.remove(id);
    }

    // --- internal ---

    static final class WorkspaceState {
        String executionMode;
        String state;
        String workspaceRoot;
        String instanceId;
        boolean retain;
        String idleOutcome;
        String pausedPrompt;

        WorkspaceState(String executionMode, String state, String workspaceRoot,
                       String instanceId, boolean retain) {
            this.executionMode = executionMode;
            this.state = state;
            this.workspaceRoot = workspaceRoot;
            this.instanceId = instanceId;
            this.retain = retain;
        }
    }
}
