package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ClaimStore;
import de.augmentia.oc.engine.testing.InMemoryClaims;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link ClaimStore}. */
class ClaimStoreContract {

    private ClaimStore claims;
    private SessionId session;

    @BeforeEach
    void setUp() {
        claims = new InMemoryClaims();
        session = SessionId.create();
    }

    @Test
    void acquireSucceedsOnlyOnce() {
        Option<Claim> first = claims.acquire(session, Instant.EPOCH);
        assertThat(first.isPresent()).isTrue();
        assertThat(first.orElseThrow().sessionId()).isEqualTo(session);
        assertThat(first.orElseThrow().resumeAttempts()).isZero();

        assertThat(claims.acquire(session, Instant.EPOCH).isEmpty()).isTrue();
        assertThat(claims.open()).hasSize(1);
    }

    @Test
    void releaseMakesTheClaimAcquirableAgain() {
        claims.acquire(session, Instant.EPOCH);
        claims.release(session);
        assertThat(claims.open()).isEmpty();

        Claim second = claims.acquire(session, Instant.EPOCH).orElseThrow();
        assertThat(second.resumeAttempts()).isEqualTo(1);
    }

    @Test
    void claimsAreSeparatedBySession() {
        SessionId other = SessionId.create();
        claims.acquire(session, Instant.EPOCH);
        claims.acquire(other, Instant.EPOCH);

        assertThat(claims.open()).hasSize(2);
        claims.release(session);
        assertThat(claims.open()).extracting(Claim::sessionId).containsExactly(other);
    }

    @Test
    void releasingAnUnknownSessionIsANoOp() {
        claims.release(SessionId.create());
        assertThat(claims.open()).isEmpty();
    }
}
