package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.Location;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.port.store.SessionStore;
import de.augmentia.oc.engine.runtime.session.core.SessionRetention;
import de.augmentia.oc.engine.testing.FixedClock;
import de.augmentia.oc.engine.testing.InMemoryJournal;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRetentionTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void purgeExpiredDeletesOldSessionsButKeepsFreshAndProtectedOnes() {
        FakeSessions sessions = new FakeSessions();
        SessionId old = SessionId.create();
        SessionId protectedOld = SessionId.create();
        SessionId fresh = SessionId.create();
        sessions.put(info(old, NOW.minusSeconds(15L * 24 * 3600)));
        sessions.put(info(protectedOld, NOW.minusSeconds(20L * 24 * 3600)));
        sessions.put(info(fresh, NOW.minusSeconds(13L * 24 * 3600)));

        SessionRetention retention = new SessionRetention(sessions, new InMemoryJournal(),
                new FixedClock(NOW), 14);

        int deleted = retention.purgeExpired(id -> id.equals(protectedOld));

        assertThat(deleted).isEqualTo(1);
        assertThat(sessions.info(old).isEmpty()).isTrue();
        assertThat(sessions.info(protectedOld).isPresent()).isTrue();
        assertThat(sessions.info(fresh).isPresent()).isTrue();
    }

    @Test
    void deleteRemovesSessionAndItsEvents() {
        FakeSessions sessions = new FakeSessions();
        InMemoryJournal journal = new InMemoryJournal();
        SessionId id = SessionId.create();
        sessions.put(info(id, NOW));
        journal.append(new EventDraft(id.value(), 1, new SessionEvent.ExecutionStarted(id)));

        SessionRetention retention = new SessionRetention(sessions, journal,
                new FixedClock(NOW), 14);

        assertThat(retention.delete(id)).isTrue();
        assertThat(sessions.info(id).isEmpty()).isTrue();
        assertThat(journal.count(id.value())).isZero();
        assertThat(retention.delete(id)).isFalse();
    }

    @Test
    void nonPositiveRetentionIsDisabled() {
        FakeSessions sessions = new FakeSessions();
        SessionId old = SessionId.create();
        sessions.put(info(old, NOW.minusSeconds(100L * 24 * 3600)));

        SessionRetention retention = new SessionRetention(sessions, new InMemoryJournal(),
                new FixedClock(NOW), 0);

        assertThat(retention.purgeExpired(null)).isZero();
        assertThat(sessions.info(old).isPresent()).isTrue();
    }

    private static SessionInfo info(SessionId id, Instant updated) {
        return new SessionInfo(id, ProjectId.create(),
                new Location(Path.of("/work"), ProjectId.create(), Option.none()), "title",
                AgentId.create(), Option.none(), List.of(), Money.zero(), TokenCount.zero(),
                updated, updated, Option.none(), Option.none(), 0, Option.none());
    }

    private static final class FakeSessions implements SessionStore {

        private final Map<SessionId, SessionInfo> entries = new LinkedHashMap<>();

        @Override
        public void put(SessionInfo info) {
            entries.put(info.id(), info);
        }

        @Override
        public void putInbox(InboxEntry entry) {
        }

        @Override
        public void removeInbox(SessionId session, InboxId id) {
        }

        @Override
        public List<SessionInfo> list() {
            return new ArrayList<>(entries.values());
        }

        @Override
        public boolean delete(SessionId session) {
            return entries.remove(session) != null;
        }

        @Override
        public Option<SessionInfo> info(SessionId session) {
            return Option.of(entries.get(session));
        }

        @Override
        public List<InboxEntry> inbox(SessionId session) {
            return List.of();
        }

        @Override
        public Option<InboxEntry> inboxItem(SessionId session, InboxId id) {
            return Option.none();
        }
    }
}
