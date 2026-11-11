package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.model.SessionId;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory implementation of {@link SubagentSessionRegistry}. */
public final class InMemorySubagentSessionRegistry implements SubagentSessionRegistry {

    private final ConcurrentHashMap<SessionId, java.util.Set<SessionId>> children =
            new ConcurrentHashMap<>();

    @Override
    public void registerChild(SessionId parent, SessionId child) {
        if (parent == null || child == null) return;
        children.computeIfAbsent(parent, k -> ConcurrentHashMap.newKeySet()).add(child);
    }

    @Override
    public Set<SessionId> children(SessionId parent) {
        if (parent == null) return Set.of();
        Set<SessionId> c = children.get(parent);
        return c == null ? Set.of() : Set.copyOf(c);
    }

    @Override
    public Set<SessionId> descendants(SessionId parent) {
        if (parent == null) return Set.of();
        java.util.Set<SessionId> result = ConcurrentHashMap.newKeySet();
        java.util.Deque<SessionId> stack = new java.util.ArrayDeque<>();
        stack.push(parent);
        while (!stack.isEmpty()) {
            SessionId cur = stack.pop();
            Set<SessionId> c = children.get(cur);
            if (c == null) continue;
            for (SessionId ch : c) {
                if (result.add(ch)) stack.push(ch);
            }
        }
        return Set.copyOf(result);
    }

    @Override
    public void remove(SessionId id) {
        if (id == null) return;
        children.remove(id);
        for (java.util.Set<SessionId> c : children.values()) {
            c.remove(id);
        }
    }
}