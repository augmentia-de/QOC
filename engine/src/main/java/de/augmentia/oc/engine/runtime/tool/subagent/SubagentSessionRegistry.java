package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.model.SessionId;

import java.util.Set;

/**
 * Runtime-owned registry of parent/child session relationships created by
 * subagent invocations. Lives in the engine module so subagent tracking does
 * not depend on the HTTP/UI layer.
 *
 * The registry is the single source of truth for cascade-cancel and
 * "is anything running under this session" queries. Thread interruption and
 * task cancellation stay in the calling (HTTP) layer, which consults this
 * registry to discover the affected sessions.
 */
public interface SubagentSessionRegistry {

    /** Records that {@code child} was spawned by {@code parent}. */
    void registerChild(SessionId parent, SessionId child);

    /** Direct children of {@code parent}. */
    Set<SessionId> children(SessionId parent);

    /** All descendants of {@code parent} (recursive). */
    Set<SessionId> descendants(SessionId parent);

    /** Removes {@code id} from every parent's child set and its own entry. */
    void remove(SessionId id);
}