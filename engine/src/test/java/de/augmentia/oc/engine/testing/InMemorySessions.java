package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.port.store.SessionReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fixed sessions plus an in-memory inbox. */
public final class InMemorySessions implements SessionReader {

    private final Map<SessionId, SessionInfo> sessions = new LinkedHashMap<>();
    private final Map<SessionId, List<InboxEntry>> inbox = new LinkedHashMap<>();

    public synchronized void put(SessionInfo info) {
        sessions.put(info.id(), info);
    }

    public synchronized void putInbox(InboxEntry entry) {
        List<InboxEntry> list = inbox.computeIfAbsent(entry.sessionId(), key -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(entry.id())) {
                list.set(i, entry);
                return;
            }
        }
        list.add(entry);
    }

    public synchronized void removeInbox(SessionId session, InboxId id) {
        List<InboxEntry> list = inbox.get(session);
        if (list != null) {
            list.removeIf(entry -> entry.id().equals(id));
        }
    }

    @Override
    public synchronized Option<SessionInfo> info(SessionId session) {
        return Option.of(sessions.get(session));
    }

    @Override
    public synchronized List<InboxEntry> inbox(SessionId session) {
        return List.copyOf(inbox.getOrDefault(session, List.of()));
    }

    @Override
    public synchronized Option<InboxEntry> inboxItem(SessionId session, InboxId id) {
        return Option.of(inbox.getOrDefault(session, List.of()).stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .orElse(null));
    }
}
