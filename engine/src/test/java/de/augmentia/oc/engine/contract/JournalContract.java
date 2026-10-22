package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.StoredEvent;
import de.augmentia.oc.engine.core.port.store.Journal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link Journal}, run against the fake and PostgreSQL. */
class JournalContract {

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#journals")
    void appendAssignsGaplessSequencesPerAggregate(Journal journal) {
        String first = aggregate();
        String second = aggregate();

        assertThat(journal.append(draft(first))).isEqualTo(Seq.of(1));
        assertThat(journal.append(draft(first))).isEqualTo(Seq.of(2));
        assertThat(journal.append(draft(second))).isEqualTo(Seq.of(1));
        assertThat(journal.append(draft(first))).isEqualTo(Seq.of(3));
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#journals")
    void historyIsExclusiveAfterAndPerAggregate(Journal journal) {
        String session = aggregate();
        journal.append(draft(session));
        journal.append(draft(session));
        journal.append(draft(aggregate()));

        List<StoredEvent> all = journal.history(session, Seq.zero());
        assertThat(all).extracting(event -> event.seq().value()).containsExactly(1L, 2L);

        List<StoredEvent> afterFirst = journal.history(session, Seq.of(1));
        assertThat(afterFirst).hasSize(1);
        assertThat(afterFirst.getFirst().seq()).isEqualTo(Seq.of(2));
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#journals")
    void historyOfTypeFiltersByEventType(Journal journal) {
        String session = aggregate();
        journal.append(draft(session, "ExecutionStarted"));
        journal.append(draft(session, "InboxDelivered"));
        journal.append(draft(session, "ExecutionStarted"));

        assertThat(journal.historyOfType(session, "ExecutionStarted", Seq.zero())).hasSize(2);
        assertThat(journal.historyOfType(session, "InboxDelivered", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session, "Unknown", Seq.zero())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#journals")
    void appendAllReturnsSequencesInOrder(Journal journal) {
        String session = aggregate();
        List<Seq> sequences = journal.appendAll(List.of(draft(session), draft(session), draft(aggregate())));
        assertThat(sequences).containsExactly(Seq.of(1), Seq.of(2), Seq.of(1));
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#journals")
    void storedEventCarriesTypeVersionAndPayload(Journal journal) {
        String session = aggregate();
        Seq seq = journal.append(draft(session));
        StoredEvent event = journal.history(session, Seq.zero()).getFirst();
        assertThat(event.seq()).isEqualTo(seq);
        assertThat(event.type()).isEqualTo("ExecutionStarted");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.payload()).isInstanceOf(SessionEvent.ExecutionStarted.class);
    }

    private static String aggregate() {
        return "contract-" + UUID.randomUUID();
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
