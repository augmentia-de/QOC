package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.runtime.execution.Coordinator;
import de.augmentia.oc.engine.runtime.execution.ExecutionOutcome;
import de.augmentia.oc.engine.runtime.execution.Recovery;
import de.augmentia.oc.engine.testing.FixedClock;
import de.augmentia.oc.engine.testing.InMemoryClaims;
import de.augmentia.oc.engine.testing.InMemoryJournal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryTest {

    private InMemoryJournal journal;
    private InMemoryClaims claims;
    private Coordinator coordinator;

    @BeforeEach
    void setUp() {
        journal = new InMemoryJournal();
        claims = new InMemoryClaims();
        coordinator = new Coordinator(claims, journal, new FixedClock(),
                (session, execution, scope) -> new ExecutionOutcome.Succeeded());
    }

    @Test
    void openClaimIsContinuedWithSyntheticNotice() {
        SessionId session = SessionId.create();
        claims.acquire(session, new FixedClock().now());

        int started = new Recovery(claims, journal, coordinator).recover();

        assertThat(started).isEqualTo(1);
        assertThat(journal.historyOfType(session.value(), "SyntheticRaised", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session.value(), "ExecutionStarted", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session.value(), "ExecutionSucceeded", Seq.zero())).hasSize(1);
        assertThat(claims.open()).isEmpty();
    }

    @Test
    void tooManyAttemptsFailsAndReleasesTheClaim() {
        SessionId session = SessionId.create();
        for (int i = 0; i < Recovery.MAX_RESUME_ATTEMPTS; i++) {
            claims.acquire(session, new FixedClock().now());
            claims.release(session);
        }
        claims.acquire(session, new FixedClock().now());

        int started = new Recovery(claims, journal, coordinator).recover();

        assertThat(started).isZero();
        assertThat(journal.historyOfType(session.value(), "ExecutionFailed", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session.value(), "SyntheticRaised", Seq.zero())).isEmpty();
        assertThat(claims.open()).isEmpty();
    }

    @Test
    void activeSessionIsSkipped() {
        SessionId session = SessionId.create();
        // Simulate an active execution by starting a coordinator run that blocks.
        // Recovery must not start a second execution for the same session.
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Coordinator blocking = new Coordinator(claims, journal, new FixedClock(), (id, execution, scope) -> {
            entered.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecutionOutcome.Succeeded();
        });
        Thread thread = new Thread(() -> blocking.wake(session, de.augmentia.oc.engine.runtime.execution.ExecutionScope.INPUT));
        thread.start();
        try {
            entered.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int started = new Recovery(claims, journal, blocking).recover();

        assertThat(started).isZero();
        release.countDown();
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
