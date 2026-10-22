package de.augmentia.oc.engine.runtime.execution;

import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.InterruptReason;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.ClaimStore;
import de.augmentia.oc.engine.core.port.store.Journal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Execution coordination: one active execution per session, a doorbell that
 * coalesces wakes, exactly one terminal event per execution period and the
 * claim rules of I-03.
 */
public final class Coordinator {

    private final ClaimStore claims;
    private final Journal journal;
    private final Clock clock;
    private final ExecutionRunner runner;
    private final Map<SessionId, SessionExecution> active = new ConcurrentHashMap<>();

    public Coordinator(ClaimStore claims, Journal journal, Clock clock, ExecutionRunner runner) {
        if (claims == null || journal == null || clock == null || runner == null) {
            throw new IllegalArgumentException("coordinator dependencies must not be null");
        }
        this.claims = claims;
        this.journal = journal;
        this.clock = clock;
        this.runner = runner;
    }

    /** @return true when a new execution was started, false when coalesced. */
    public boolean wake(SessionId session, ExecutionScope scope) {
        if (session == null || scope == null) throw new IllegalArgumentException("session and scope must not be null");
        SessionExecution fresh;
        synchronized (this) {
            SessionExecution running = active.get(session);
            if (running != null) {
                running.ring(scope);
                return false;
            }
            fresh = new SessionExecution();
            active.put(session, fresh);
        }
        run(session, fresh, scope);
        return true;
    }

    /** I-03-05: interrupting an inactive session is a no-op. */
    public boolean interrupt(SessionId session) {
        SessionExecution execution = active.get(session);
        if (execution == null) return false;
        execution.stop(InterruptReason.USER);
        return true;
    }

    /** Stops all local executions; the claim stays (I-03-04). */
    public void shutdown() {
        for (SessionExecution execution : active.values()) {
            execution.stop(InterruptReason.SHUTDOWN);
        }
    }

    public boolean active(SessionId session) {
        return active.containsKey(session);
    }

    private void run(SessionId session, SessionExecution execution, ExecutionScope initialScope) {
        Option<Claim> claim = claims.acquire(session, clock.now());
        if (claim.isEmpty()) {
            active.remove(session);
            return;
        }

        journal.append(new EventDraft(session.value(), 1, new SessionEvent.ExecutionStarted(session)));

        ExecutionOutcome terminal = runner.run(session, execution, initialScope);
        if (execution.stopped()) {
            terminal = new ExecutionOutcome.Interrupted(execution.reason().orElse(InterruptReason.USER));
        }

        publishTerminal(session, terminal);
        active.remove(session);

        Option<ExecutionScope> followUp = execution.consumeDoorbell();
        if (followUp.isPresent()) {
            wake(session, followUp.orElseThrow());
        }
    }

    private void publishTerminal(SessionId session, ExecutionOutcome terminal) {
        switch (terminal) {
            case ExecutionOutcome.Succeeded succeeded -> {
                journal.append(new EventDraft(session.value(), 1,
                        new SessionEvent.ExecutionSucceeded(session)));
                claims.release(session);
            }
            case ExecutionOutcome.Failed failed -> {
                journal.append(new EventDraft(session.value(), 1,
                        new SessionEvent.ExecutionFailed(session, failed.error())));
                claims.release(session);
            }
            case ExecutionOutcome.Interrupted interrupted -> {
                journal.append(new EventDraft(session.value(), 1,
                        new SessionEvent.ExecutionInterrupted(session, interrupted.reason())));
                if (interrupted.reason() == InterruptReason.USER) {
                    claims.release(session);
                }
                // Shutdown keeps the claim for resumption (I-03-04).
            }
        }
    }
}
