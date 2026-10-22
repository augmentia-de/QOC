package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.InstructionStore;
import de.augmentia.oc.engine.testing.InMemoryInstructions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link InstructionStore}. */
class InstructionStoreContract {

    private InstructionStore store;
    private SessionId session;

    @BeforeEach
    void setUp() {
        store = new InMemoryInstructions();
        session = SessionId.create();
    }

    @Test
    void stateIsStoredPerSession() {
        InstructionState state = new InstructionState(Seq.of(1), Seq.of(5),
                Map.of("a", "1"), Map.of("a", "2"));
        store.putState(session, state);

        assertThat(store.state(session).orElseThrow()).isEqualTo(state);
        assertThat(store.state(SessionId.create()).isEmpty()).isTrue();
    }

    @Test
    void entriesAreUpsertedByKey() {
        store.putEntry(session, new InstructionEntry("key", "one", "h1"));
        store.putEntry(session, new InstructionEntry("key", "two", "h2"));
        store.putEntry(session, new InstructionEntry("other", "three", "h3"));

        assertThat(store.entries(session)).hasSize(2);
        assertThat(store.entries(session))
                .filteredOn(entry -> entry.key().equals("key"))
                .singleElement()
                .extracting(InstructionEntry::value)
                .isEqualTo("two");
    }

    @Test
    void entryCanBeRemoved() {
        store.putEntry(session, new InstructionEntry("key", "value", "hash"));
        store.removeEntry(session, "key");
        assertThat(store.entries(session)).isEmpty();
    }

    @Test
    void blobsAreStoredByHash() {
        store.putBlob("hash", "text");
        assertThat(store.blob("hash").orElseThrow()).isEqualTo("text");
        assertThat(store.blob("missing").isEmpty()).isTrue();
    }

    @Test
    void sessionsAreSeparated() {
        store.putEntry(session, new InstructionEntry("key", "value", "hash"));
        assertThat(store.entries(SessionId.create())).isEmpty();
    }
}
