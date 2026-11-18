package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;

import java.util.List;

/** Session read/write model and pending inbox. */
public interface SessionStore extends SessionReader {

    void put(SessionInfo info);

    /** Sets the idle outcome (completed/cancelled/paused/failed) for a session. */
    void setIdleOutcome(SessionId id, String outcome);

    void putInbox(InboxEntry entry);

    void removeInbox(SessionId session, InboxId id);

    List<SessionInfo> list();

    /** Lists sessions whose title starts with the given prefix. */
    default List<SessionInfo> listByTitlePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return list();
        return list().stream()
                .filter(info -> info.title() != null && info.title().startsWith(prefix))
                .toList();
    }

    /** Deletes one session and its dependent projections. */
    boolean delete(SessionId session);
}
