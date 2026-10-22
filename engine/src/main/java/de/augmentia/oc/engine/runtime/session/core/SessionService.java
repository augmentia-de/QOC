package de.augmentia.oc.engine.runtime.session.core;

import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.InboxItem;
import de.augmentia.oc.engine.core.model.Location;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.runtime.SessionWake;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.core.port.store.SessionReader;
import de.augmentia.oc.engine.runtime.session.delivery.Delivery;

import java.time.Instant;
import java.util.function.BiConsumer;

/** Session write side: create, admit and deliver. */
public final class SessionService {

    private final Journal journal;
    private final Admission admission;
    private final Delivery delivery;

    public SessionService(Journal journal, SessionReader sessions, SessionWake wake,
                          BiConsumer<SessionId, InboxId> removeInbox) {
        if (journal == null || sessions == null || wake == null || removeInbox == null) {
            throw new IllegalArgumentException("session dependencies must not be null");
        }
        this.journal = journal;
        this.admission = new Admission(journal, sessions, wake);
        this.delivery = new Delivery(journal, sessions, removeInbox);
    }

    public SessionId create(Location location, AgentId agent, Option<ModelRef> model, Instant at) {
        if (location == null || agent == null) {
            throw new IllegalArgumentException("location and agent must not be null");
        }
        SessionId session = SessionId.create();
        journal.append(new EventDraft(session.value(), 1,
                new SessionEvent.SessionCreated(session, location, agent,
                        model == null ? Option.none() : model, at == null ? Instant.EPOCH : at)));
        return session;
    }

    public AdmissionResult enqueue(SessionId session, InboxId id, InboxItem item) {
        return admission.admit(session, id, item);
    }

    public int deliver(SessionId session) {
        return delivery.deliver(session);
    }
}
