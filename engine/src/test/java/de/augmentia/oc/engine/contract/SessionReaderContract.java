package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Delivery;
import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.InboxItem;
import de.augmentia.oc.engine.core.model.Location;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.port.store.SessionReader;
import de.augmentia.oc.engine.testing.InMemorySessions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link SessionReader}. */
class SessionReaderContract {

    private InMemorySessions sessions;
    private SessionId session;

    @BeforeEach
    void setUp() {
        sessions = new InMemorySessions();
        session = SessionId.create();
    }

    @Test
    void infoIsReturnedOnlyWhenStored() {
        assertThat(sessions.info(session).isEmpty()).isTrue();

        SessionInfo info = info(session);
        sessions.put(info);

        assertThat(sessions.info(session).orElseThrow()).isEqualTo(info);
        assertThat(sessions.info(SessionId.create()).isEmpty()).isTrue();
    }

    @Test
    void inboxKeepsInsertionOrderAndReplacesSameId() {
        InboxEntry first = entry(InboxId.create(), "first", Seq.of(1));
        InboxEntry second = entry(InboxId.create(), "second", Seq.of(2));
        sessions.putInbox(first);
        sessions.putInbox(second);

        assertThat(sessions.inbox(session)).containsExactly(first, second);

        InboxEntry replacement = entry(first.id(), "replaced", Seq.of(1));
        sessions.putInbox(replacement);
        assertThat(sessions.inbox(session)).containsExactly(replacement, second);
    }

    @Test
    void inboxItemIsFoundByIdAndCanBeRemoved() {
        InboxEntry entry = entry(InboxId.create(), "text", Seq.of(1));
        sessions.putInbox(entry);

        assertThat(sessions.inboxItem(session, entry.id()).orElseThrow()).isEqualTo(entry);
        assertThat(sessions.inboxItem(session, InboxId.create()).isEmpty()).isTrue();

        sessions.removeInbox(session, entry.id());
        assertThat(sessions.inbox(session)).isEmpty();
    }

    @Test
    void unknownInboxIsEmpty() {
        assertThat(sessions.inbox(SessionId.create())).isEmpty();
    }

    private SessionInfo info(SessionId id) {
        return new SessionInfo(id, ProjectId.create(),
                new Location(Path.of("/tmp"), ProjectId.create(), Option.none()),
                "title", AgentId.create(), Option.none(), List.of(), null, null, Instant.EPOCH,
                Instant.EPOCH, Option.none(), Option.none(), 0, Option.none());
    }

    private InboxEntry entry(InboxId id, String text, Seq seq) {
        return new InboxEntry(id, session,
                new InboxItem.UserItem(text, List.of(), List.of(), List.of(), Map.of()),
                Delivery.QUEUE, seq, Instant.EPOCH);
    }
}
