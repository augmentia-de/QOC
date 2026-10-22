package de.augmentia.oc.engine.runtime.session.core;

import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.InboxItem;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.port.runtime.SessionWake;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.core.port.store.SessionReader;

/**
 * Admission order is binding: idempotency first, then (preparation), then the
 * durable {@code InboxEnqueued} event, and only afterwards scheduling.
 */
public final class Admission {

    private final Journal journal;
    private final SessionReader sessions;
    private final SessionWake wake;

    public Admission(Journal journal, SessionReader sessions, SessionWake wake) {
        if (journal == null || sessions == null || wake == null) {
            throw new IllegalArgumentException("admission dependencies must not be null");
        }
        this.journal = journal;
        this.sessions = sessions;
        this.wake = wake;
    }

    public AdmissionResult admit(SessionId session, InboxId id, InboxItem item) {
        return admit(session, id, item, true);
    }

    public AdmissionResult admit(SessionId session, InboxId id, InboxItem item, boolean schedule) {
        if (session == null || id == null || item == null) {
            throw new IllegalArgumentException("session, id and item must not be null");
        }
        Option<SessionInfo> info = sessions.info(session);
        if (info.isEmpty()) return new AdmissionResult.SessionNotFound();

        Option<InboxEntry> existing = sessions.inboxItem(session, id);
        if (existing.isPresent()) {
            InboxEntry entry = existing.orElseThrow();
            if (!entry.item().getClass().equals(item.getClass()) || !entry.sessionId().equals(session)) {
                return new AdmissionResult.Conflict("inbox id already used for a different input");
            }
            return new AdmissionResult.Admitted(id, false);
        }

        Seq enqueuedSeq = Seq.of(journal.history(session.value(), Seq.zero()).size() + 1L);
        journal.append(new EventDraft(session.value(), 1,
                new SessionEvent.InboxEnqueued(session, id, item, enqueuedSeq)));

        if (schedule) {
            wake.wake(session);
        }
        return new AdmissionResult.Admitted(id, true);
    }
}
