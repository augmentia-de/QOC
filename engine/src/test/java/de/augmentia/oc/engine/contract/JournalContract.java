package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.StoredEvent;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.testing.InMemoryJournal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link Journal}, currently run against the in-memory fake. */
class JournalContract {

    private Journal journal;

    @BeforeEach
    void setUp() {
        journal = new InMemoryJournal();
    }

    @Test
    void appendAssignsGaplessSequencesPerAggregate() {
        String first = "session-a";
        String second = "session-b";

        assertThat(journal.append(draft(first))).isEqualTo(Seq.of(1));
        assertThat(journal.append(draft(first))).isEqualTo(Seq.of(2));
        assertThat(journal.append(draft(second))).isEqualTo(Seq.of(1));
        assertThat(journal.append(draft(first))).isEqualTo(Seq.of(3));
    }

    @Test
    void historyIsExclusiveAfterAndPerAggregate() {
        String session = "session-a";
        journal.append(draft(session));
        journal.append(draft(session));
        journal.append(draft("other"));

        List<StoredEvent> all = journal.history(session, Seq.zero());
        assertThat(all).extracting(event -> event.seq().value()).containsExactly(1L, 2L);

        List<StoredEvent> afterFirst = journal.history(session, Seq.of(1));
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.getFirst().seq()).isEqualTo(Seq.of(2));
    }

    @Test
    void historyOfTypeFiltersByEventType() {
        String session = "session-a";
        journal.append(draft(session, "ExecutionStarted"));
        journal.append(draft(session, "InboxDelivered"));
        journal.append(draft(session, "ExecutionStarted"));

        assertThat(journal.historyOfType(session, "ExecutionStarted", Seq.zero())).hasSize(2);
        assertThat(journal.historyOfType(session, "InboxDelivered", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session, "Unknown", Seq.zero())).isEmpty();
    }

    @Test
    void appendAllReturnsSequencesInOrder() {
        List<Seq> sequences = journal.appendAll(List.of(draft("a"), draft("a"), draft("b")));
        assertThat(sequences).containsExactly(Seq.of(1), Seq.of(2), Seq.of(1));
    }

    @Test
    void storedEventCarriesTypeVersionAndPayload() {
        Seq seq = journal.append(draft("session-a"));
        StoredEvent event = journal.history("session-a", Seq.zero()).getFirst();
        assertThat(event.seq()).isEqualTo(seq);
        assertThat(event.type()).isEqualTo("ExecutionStarted");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.payload()).isInstanceOf(SessionEvent.ExecutionStarted.class);
    }

    private static EventDraft draft(String aggregate) {
        return draft(aggregate, "ExecutionStarted");
    }

    private static EventDraft draft(String aggregate, String type) {
        var payload = switch (type) {
            case "InboxDelivered" -> new SessionEvent.InboxDelivered(SessionId.create(),
                    de.augmentia.oc.engine.core.model.InboxId.create());
            case "ExecutionStarted" -> new SessionEvent.ExecutionStarted(SessionId.create());
            default -> throw new IllegalArgumentException(type);
        };
        return new EventDraft(aggregate, 1, payload);
    }
}
