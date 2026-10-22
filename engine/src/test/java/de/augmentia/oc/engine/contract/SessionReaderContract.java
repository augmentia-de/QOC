package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.Delivery;
import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.InboxItem;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of SessionReader, run against the fake and PostgreSQL. */
class SessionReaderContract {

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#sessionReaders")
    void infoIsReturnedOnlyForStoredSessions(Contracts.SessionFixture fixture) {
        assertThat(fixture.reader().info(fixture.session()).orElseThrow().id())
                .isEqualTo(fixture.session());
        assertThat(fixture.reader().info(SessionId.create()).isEmpty()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#sessionReaders")
    void inboxKeepsOrderAndReplacesSameId(Contracts.SessionFixture fixture) {
        InboxEntry first = entry(fixture.session(), InboxId.create(), "first", Seq.of(1));
        InboxEntry second = entry(fixture.session(), InboxId.create(), "second", Seq.of(2));
        fixture.putInbox().accept(first);
        fixture.putInbox().accept(second);

        assertThat(fixture.reader().inbox(fixture.session())).containsExactly(first, second);

        InboxEntry replacement = entry(fixture.session(), first.id(), "replaced", Seq.of(1));
        fixture.putInbox().accept(replacement);
        assertThat(fixture.reader().inbox(fixture.session())).containsExactly(replacement, second);
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#sessionReaders")
    void inboxItemIsFoundByIdAndCanBeRemoved(Contracts.SessionFixture fixture) {
        InboxEntry entry = entry(fixture.session(), InboxId.create(), "text", Seq.of(1));
        fixture.putInbox().accept(entry);

        assertThat(fixture.reader().inboxItem(fixture.session(), entry.id()).orElseThrow())
                .isEqualTo(entry);
        assertThat(fixture.reader().inboxItem(fixture.session(), InboxId.create()).isEmpty()).isTrue();

        fixture.removeInbox().accept(fixture.session(), entry.id());
        assertThat(fixture.reader().inbox(fixture.session())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#sessionReaders")
    void unknownInboxIsEmpty(Contracts.SessionFixture fixture) {
        assertThat(fixture.reader().inbox(SessionId.create())).isEmpty();
    }

    private static InboxEntry entry(SessionId session, InboxId id, String text, Seq seq) {
        return new InboxEntry(id, session,
                new InboxItem.UserItem(text, List.of(), List.of(), List.of(), Map.of()),
                Delivery.QUEUE, seq, Instant.EPOCH);
    }
}
