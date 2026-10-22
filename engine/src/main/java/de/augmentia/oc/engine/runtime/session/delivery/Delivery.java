package de.augmentia.oc.engine.runtime.session.delivery;

import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.core.port.store.SessionReader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Delivery as a transaction: parked queue inputs are released one at a time;
 * steer inputs are released immediately. Per-session serialization keeps
 * admission, delivery and cancellation from overtaking each other.
 */
public final class Delivery {

    private final Journal journal;
    private final SessionReader sessions;
    private final BiConsumer<SessionId, InboxId> removeInbox;
    private final Map<SessionId, Object> locks = new ConcurrentHashMap<>();

    public Delivery(Journal journal, SessionReader sessions, BiConsumer<SessionId, InboxId> removeInbox) {
        if (journal == null || sessions == null || removeInbox == null) {
            throw new IllegalArgumentException("delivery dependencies must not be null");
        }
        this.journal = journal;
        this.sessions = sessions;
        this.removeInbox = removeInbox;
    }

    public int deliver(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        Object lock = locks.computeIfAbsent(session, key -> new Object());
        synchronized (lock) {
            List<InboxEntry> inbox = sessions.inbox(session);
            List<InboxEntry> steer = inbox.stream()
                    .filter(entry -> entry.delivery() == de.augmentia.oc.engine.core.model.Delivery.STEER)
                    .toList();
            if (!steer.isEmpty()) {
                return deliverAll(session, steer);
            }

            List<InboxEntry> queue = new ArrayList<>(inbox.stream()
                    .filter(entry -> entry.delivery() == de.augmentia.oc.engine.core.model.Delivery.QUEUE)
                    .toList());
            queue.sort(Comparator.comparing(entry -> entry.enqueuedSeq().value()));
            if (queue.isEmpty()) return 0;
            return deliverAll(session, List.of(queue.getFirst()));
        }
    }

    private int deliverAll(SessionId session, List<InboxEntry> entries) {
        int delivered = 0;
        for (InboxEntry entry : entries) {
            journal.append(new EventDraft(session.value(), 1,
                    new SessionEvent.InboxDelivered(session, entry.id())));
            removeInbox.accept(session, entry.id());
            delivered++;
        }
        return delivered;
    }
}
