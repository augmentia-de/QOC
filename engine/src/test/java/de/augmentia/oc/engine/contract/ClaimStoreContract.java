package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of ClaimStore, run against the fake and PostgreSQL. */
class ClaimStoreContract {

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#claims")
    void acquireSucceedsOnlyOnce(Contracts.ClaimFixture fixture) {
        Option<Claim> first = fixture.claims().acquire(fixture.session(), Instant.EPOCH);
        assertThat(first.isPresent()).isTrue();
        assertThat(first.orElseThrow().sessionId()).isEqualTo(fixture.session());
        assertThat(first.orElseThrow().resumeAttempts()).isZero();

        assertThat(fixture.claims().acquire(fixture.session(), Instant.EPOCH).isEmpty()).isTrue();
        assertThat(fixture.claims().open()).extracting(Claim::sessionId).contains(fixture.session());
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#claims")
    void releaseMakesTheClaimAcquirableAgain(Contracts.ClaimFixture fixture) {
        fixture.claims().acquire(fixture.session(), Instant.EPOCH);
        fixture.claims().release(fixture.session());
        assertThat(fixture.claims().open()).extracting(Claim::sessionId).doesNotContain(fixture.session());

        Claim second = fixture.claims().acquire(fixture.session(), Instant.EPOCH).orElseThrow();
        assertThat(second.resumeAttempts()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#claims")
    void claimsAreSeparatedBySession(Contracts.ClaimFixture fixture) {
        SessionId other = fixture.newSession().get();
        fixture.claims().acquire(fixture.session(), Instant.EPOCH);
        fixture.claims().acquire(other, Instant.EPOCH);

        assertThat(fixture.claims().open()).extracting(Claim::sessionId)
                .contains(fixture.session(), other);
        fixture.claims().release(fixture.session());
        assertThat(fixture.claims().open()).extracting(Claim::sessionId)
                .contains(other).doesNotContain(fixture.session());
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#claims")
    void releasingAnUnknownSessionIsANoOp(Contracts.ClaimFixture fixture) {
        SessionId unknown = SessionId.create();
        fixture.claims().release(unknown);
        assertThat(fixture.claims().open()).extracting(Claim::sessionId).doesNotContain(unknown);
    }
}
