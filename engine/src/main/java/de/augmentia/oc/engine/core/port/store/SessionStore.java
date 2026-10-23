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

    void putInbox(InboxEntry entry);

    void removeInbox(SessionId session, InboxId id);

    List<SessionInfo> list();
}
