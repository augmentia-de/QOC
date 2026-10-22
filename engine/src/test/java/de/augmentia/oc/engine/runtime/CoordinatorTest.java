package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.InterruptReason;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.runtime.execution.Coordinator;
import de.augmentia.oc.engine.runtime.execution.ExecutionOutcome;
import de.augmentia.oc.engine.runtime.execution.ExecutionScope;
import de.augmentia.oc.engine.testing.FixedClock;
import de.augmentia.oc.engine.testing.InMemoryClaims;
import de.augmentia.oc.engine.testing.InMemoryJournal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CoordinatorTest {

    private InMemoryJournal journal;
    private InMemoryClaims claims;
    private FixedClock clock;

    @BeforeEach
    void setUp() {
        journal = new InMemoryJournal();
        claims = new InMemoryClaims();
        clock = new FixedClock();
    }

    @Test
    void successfulExecutionPublishesOneTerminalEventAndReleasesTheClaim() {
        SessionId session = SessionId.create();
        Coordinator coordinator = coordinator((id, execution, scope) -> new ExecutionOutcome.Succeeded());

        assertThat(coordinator.wake(session, ExecutionScope.INPUT)).isTrue();

        assertThat(journal.historyOfType(session.value(), "ExecutionStarted", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session.value(), "ExecutionSucceeded", Seq.zero())).hasSize(1);
        assertThat(claims.open()).isEmpty();
        assertThat(coordinator.active(session)).isFalse();
    }

    @Test
    void interruptingAnInactiveSessionIsANoOp() {
        Coordinator coordinator = coordinator((id, execution, scope) -> new ExecutionOutcome.Succeeded());
        assertThat(coordinator.interrupt(SessionId.create())).isFalse();
    }

    @Test
    void userInterruptStopsTheRunAndReleasesTheClaim() throws Exception {
        SessionId session = SessionId.create();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Coordinator coordinator = coordinator((id, execution, scope) -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecutionOutcome.Succeeded();
        });

        Thread thread = new Thread(() -> coordinator.wake(session, ExecutionScope.INPUT));
        thread.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.interrupt(session)).isTrue();
        release.countDown();
        thread.join(5_000);

        assertThat(journal.historyOfType(session.value(), "ExecutionInterrupted", Seq.zero())).hasSize(1);
        assertThat(journal.history(session.value(), Seq.zero()).stream()
                .filter(event -> event.type().equals("ExecutionInterrupted"))
                .findFirst().orElseThrow().payload())
                .isEqualTo(new de.augmentia.oc.engine.core.model.SessionEvent.ExecutionInterrupted(
                        session, InterruptReason.USER));
        assertThat(claims.open()).isEmpty();
    }

    @Test
    void shutdownInterruptKeepsTheClaim() throws Exception {
        SessionId session = SessionId.create();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Coordinator coordinator = coordinator((id, execution, scope) -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecutionOutcome.Succeeded();
        });

        Thread thread = new Thread(() -> coordinator.wake(session, ExecutionScope.INPUT));
        thread.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        coordinator.shutdown();
        release.countDown();
        thread.join(5_000);

        assertThat(journal.historyOfType(session.value(), "ExecutionInterrupted", Seq.zero())).hasSize(1);
        assertThat(claims.open()).hasSize(1);
    }

    @Test
    void workDuringThePassStartsExactlyOneFollowUpExecution() {
        SessionId session = SessionId.create();
        AtomicInteger runs = new AtomicInteger();
        Coordinator coordinator = coordinator((id, execution, scope) -> {
            runs.incrementAndGet();
            if (runs.get() == 1) {
                execution.ring(ExecutionScope.INPUT);
            }
            return new ExecutionOutcome.Succeeded();
        });

        coordinator.wake(session, ExecutionScope.INPUT);

        assertThat(runs.get()).isEqualTo(2);
        assertThat(journal.historyOfType(session.value(), "ExecutionStarted", Seq.zero())).hasSize(2);
        assertThat(journal.historyOfType(session.value(), "ExecutionSucceeded", Seq.zero())).hasSize(2);
        assertThat(claims.open()).isEmpty();
    }

    @Test
    void twoSessionsRunInParallel() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Coordinator coordinator = coordinator((id, execution, scope) -> {
            bothEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecutionOutcome.Succeeded();
        });

        SessionId first = SessionId.create();
        SessionId second = SessionId.create();
        Thread a = new Thread(() -> coordinator.wake(first, ExecutionScope.INPUT));
        Thread b = new Thread(() -> coordinator.wake(second, ExecutionScope.INPUT));
        a.start();
        b.start();

        assertThat(bothEntered.await(5, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        a.join(5_000);
        b.join(5_000);

        assertThat(journal.historyOfType(first.value(), "ExecutionSucceeded", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(second.value(), "ExecutionSucceeded", Seq.zero())).hasSize(1);
    }

    @Test
    void aClaimHeldElsewherePreventsTheStart() {
        SessionId session = SessionId.create();
        claims.acquire(session, clock.now());
        Coordinator coordinator = coordinator((id, execution, scope) -> new ExecutionOutcome.Succeeded());

        assertThat(coordinator.wake(session, ExecutionScope.INPUT)).isTrue();
        assertThat(journal.history(session.value(), Seq.zero())).isEmpty();
    }

    private Coordinator coordinator(de.augmentia.oc.engine.runtime.execution.ExecutionRunner runner) {
        return new Coordinator(claims, journal, clock, runner);
    }
}
