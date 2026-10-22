package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;

import java.util.List;

/** Session read model and pending inbox. */
public interface SessionReader {

    Option<SessionInfo> info(SessionId session);

    List<InboxEntry> inbox(SessionId session);

    Option<InboxEntry> inboxItem(SessionId session, InboxId id);
}
