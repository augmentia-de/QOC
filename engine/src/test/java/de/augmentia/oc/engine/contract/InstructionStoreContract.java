package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of InstructionStore, run against the fake and PostgreSQL. */
class InstructionStoreContract {

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#instructions")
    void stateIsStoredPerSession(Contracts.InstructionFixture fixture) {
        InstructionState state = new InstructionState(Seq.of(1), Seq.of(5),
                Map.of("a", "1"), Map.of("a", "2"));
        fixture.store().putState(fixture.session(), state);

        assertThat(fixture.store().state(fixture.session()).orElseThrow()).isEqualTo(state);
        assertThat(fixture.store().state(SessionId.create()).isEmpty()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#instructions")
    void entriesAreUpsertedByKey(Contracts.InstructionFixture fixture) {
        fixture.store().putEntry(fixture.session(), new InstructionEntry("key", "one", "h1"));
        fixture.store().putEntry(fixture.session(), new InstructionEntry("key", "two", "h2"));
        fixture.store().putEntry(fixture.session(), new InstructionEntry("other", "three", "h3"));

        assertThat(fixture.store().entries(fixture.session())).hasSize(2);
        assertThat(fixture.store().entries(fixture.session()))
                .filteredOn(entry -> entry.key().equals("key"))
                .singleElement()
                .extracting(InstructionEntry::value)
                .isEqualTo("two");
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#instructions")
    void entryCanBeRemoved(Contracts.InstructionFixture fixture) {
        fixture.store().putEntry(fixture.session(), new InstructionEntry("key", "value", "hash"));
        fixture.store().removeEntry(fixture.session(), "key");
        assertThat(fixture.store().entries(fixture.session())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#instructions")
    void blobsAreStoredByHash(Contracts.InstructionFixture fixture) {
        fixture.store().putBlob("hash", "text");
        assertThat(fixture.store().blob("hash").orElseThrow()).isEqualTo("text");
        assertThat(fixture.store().blob("missing").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#instructions")
    void sessionsAreSeparated(Contracts.InstructionFixture fixture) {
        fixture.store().putEntry(fixture.session(), new InstructionEntry("key", "value", "hash"));
        assertThat(fixture.store().entries(SessionId.create())).isEmpty();
    }
}
