package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.ArtifactRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import java.time.Instant;

/** Store for full tool output that was too large for the model context. */
public interface ToolOutputStore {

    /**
     * Stores the bytes under a session-scoped artifact id derived from
     * {@code suffix}. The suffix is usually the tool call id, which makes a
     * retry idempotent.
     */
    ArtifactRef save(SessionId session, String suffix, byte[] bytes);

    Option<ArtifactRef> find(SessionId session, String id);

    byte[] load(SessionId session, String id);

    /**
     * Copies an artifact into another session. Fork uses this to keep
     * {@code artifact://} references resolvable in the child.
     */
    default ArtifactRef copy(SessionId from, SessionId to, String id) {
        return save(to, "fork_" + id, load(from, id));
    }

    void sweep(Instant olderThan);
}
