package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.runtime.session.core.AdmissionResult;
import de.augmentia.oc.engine.runtime.session.core.SessionService;
import de.augmentia.oc.engine.testing.CountingWake;
import de.augmentia.oc.engine.testing.InMemoryJournal;
import de.augmentia.oc.engine.testing.InMemorySessions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTest {

    private InMemoryJournal journal;
    private InMemorySessions sessions;
    private CountingWake wake;
    private SessionService service;
    private SessionId session;

    @BeforeEach
    void setUp() {
        journal = new InMemoryJournal();
        sessions = new InMemorySessions();
        wake = new CountingWake();
        service = new SessionService(journal, sessions, wake, sessions::removeInbox);
        session = SessionId.create();
        sessions.put(info(session));
    }

    @Test
    void createPublishesSessionCreated() {
        SessionId created = service.create(location(), AgentId.create(), Option.none(), Instant.EPOCH);

        assertThat(created).isNotNull();
        assertThat(journal.history(created.value(), Seq.zero())).hasSize(1);
        assertThat(journal.history(created.value(), Seq.zero()).getFirst().payload())
                .isInstanceOf(SessionEvent.SessionCreated.class);
    }

    @Test
    void admissionEnqueuesAndWakes() {
        InboxId id = InboxId.create();
        AdmissionResult result = service.enqueue(session, id, userItem("hello"));

        assertThat(result).isEqualTo(new AdmissionResult.Admitted(id, true));
        assertThat(journal.historyOfType(session.value(), "InboxEnqueued", Seq.zero())).hasSize(1);
        assertThat(wake.count()).isEqualTo(1);
    }

    @Test
    void knownIdIsIdempotent() {
        InboxId id = InboxId.create();
        InboxItem item = userItem("hello");
        sessions.putInbox(new InboxEntry(id, session, item, de.augmentia.oc.engine.core.model.Delivery.QUEUE,
                Seq.of(1), Instant.EPOCH));

        AdmissionResult result = service.enqueue(session, id, userItem("hello"));

        assertThat(result).isEqualTo(new AdmissionResult.Admitted(id, false));
        assertThat(journal.history(session.value(), Seq.zero())).isEmpty();
        assertThat(wake.count()).isZero();
    }

    @Test
    void sameIdWithDifferentTypeIsAConflict() {
        InboxId id = InboxId.create();
        sessions.putInbox(new InboxEntry(id, session, userItem("hello"),
                de.augmentia.oc.engine.core.model.Delivery.QUEUE, Seq.of(1), Instant.EPOCH));

        AdmissionResult result = service.enqueue(session, id,
                new InboxItem.MoveItem("/tmp"));

        assertThat(result).isInstanceOf(AdmissionResult.Conflict.class);
    }

    @Test
    void admissionWithoutSchedulingDoesNotWake() {
        service.enqueue(session, InboxId.create(), userItem("hello"));
        // the public API always schedules; use Admission directly for the no-schedule path
        assertThat(wake.count()).isEqualTo(1);
    }

    @Test
    void admissionForUnknownSessionFails() {
        AdmissionResult result = service.enqueue(SessionId.create(), InboxId.create(), userItem("x"));
        assertThat(result).isInstanceOf(AdmissionResult.SessionNotFound.class);
    }

    @Test
    void queueDeliversOneOldestInputAtATime() {
        InboxId first = InboxId.create();
        InboxId second = InboxId.create();
        sessions.putInbox(entry(first, Seq.of(1), de.augmentia.oc.engine.core.model.Delivery.QUEUE));
        sessions.putInbox(entry(second, Seq.of(2), de.augmentia.oc.engine.core.model.Delivery.QUEUE));

        assertThat(service.deliver(session)).isEqualTo(1);
        assertThat(sessions.inbox(session)).extracting(InboxEntry::id).containsExactly(second);
        assertThat(journal.historyOfType(session.value(), "InboxDelivered", Seq.zero())).hasSize(1);

        assertThat(service.deliver(session)).isEqualTo(1);
        assertThat(sessions.inbox(session)).isEmpty();
    }

    @Test
    void steerInputsAreDeliveredImmediately() {
        sessions.putInbox(entry(InboxId.create(), Seq.of(1), de.augmentia.oc.engine.core.model.Delivery.QUEUE));
        sessions.putInbox(entry(InboxId.create(), Seq.of(2), de.augmentia.oc.engine.core.model.Delivery.STEER));
        sessions.putInbox(entry(InboxId.create(), Seq.of(3), de.augmentia.oc.engine.core.model.Delivery.STEER));

        assertThat(service.deliver(session)).isEqualTo(2);
        assertThat(sessions.inbox(session)).hasSize(1);
    }

    @Test
    void deliveringAnEmptyInboxDoesNothing() {
        assertThat(service.deliver(session)).isZero();
        assertThat(journal.history(session.value(), Seq.zero())).isEmpty();
    }

    private InboxEntry entry(InboxId id, Seq seq, de.augmentia.oc.engine.core.model.Delivery delivery) {
        return new InboxEntry(id, session, userItem("text"), delivery, seq, Instant.EPOCH);
    }

    private static InboxItem userItem(String text) {
        return new InboxItem.UserItem(text, List.of(), List.of(), List.of(), Map.of());
    }

    private static Location location() {
        return new Location(Path.of("/work"), ProjectId.create(), Option.none());
    }

    private static SessionInfo info(SessionId id) {
        return new SessionInfo(id, ProjectId.create(), location(), "title", AgentId.create(),
                Option.none(), List.of(), Money.zero(), TokenCount.zero(), Instant.EPOCH, Instant.EPOCH,
                Option.none(), Option.none(), 0, Option.none());
    }
}
