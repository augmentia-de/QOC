package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.SessionId;

import java.util.List;

/**
 * Tracks the execution mode, state and workspace root of a session without
 * touching the {@link SessionInfo} projection. Used by the ephemeral one-shot
 * mode to bind a session to a temporary directory and clean it up.
 */
public interface SessionWorkspaceStore {
    void put(SessionId id, String executionMode, String state, String workspaceRoot, String instanceId, boolean retain);

    String executionMode(SessionId id);

    String state(SessionId id);

    String workspaceRoot(SessionId id);

    boolean retain(SessionId id);

    void setPausedPrompt(SessionId id, String prompt);

    String pausedPrompt(SessionId id);

    String instanceId(SessionId id);

    void updateState(SessionId id, String state);

    /** Ephemeral sessions that are active/cleanup_pending and belong to this instance or are unowned. */
    List<SessionId> ephemeralOrphans(String instanceId);

    void deleteWorkspace(SessionId id);
}