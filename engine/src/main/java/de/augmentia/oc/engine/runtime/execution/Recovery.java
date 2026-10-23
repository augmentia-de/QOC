package de.augmentia.oc.engine.runtime.execution;

import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.port.store.ClaimStore;
import de.augmentia.oc.engine.core.port.store.Journal;

import java.util.Map;

/**
 * Recovery after a process abort (I-10-01): open claims are continued, at most
 * ten attempts; the attempt counter is durable and incremented before the run.
 */
public final class Recovery {

    public static final int MAX_RESUME_ATTEMPTS = 10;

    private final ClaimStore claims;
    private final Journal journal;
    private final Coordinator coordinator;

    public Recovery(ClaimStore claims, Journal journal, Coordinator coordinator) {
        if (claims == null || journal == null || coordinator == null) {
            throw new IllegalArgumentException("recovery dependencies must not be null");
        }
        this.claims = claims;
        this.journal = journal;
        this.coordinator = coordinator;
    }

    /** @return number of executions started. */
    public int recover() {
        int started = 0;
        for (Claim claim : claims.open()) {
            if (coordinator.active(claim.sessionId())) continue;

            if (claim.resumeAttempts() >= MAX_RESUME_ATTEMPTS) {
                journal.append(new EventDraft(claim.sessionId().value(), 1,
                        new SessionEvent.ExecutionFailed(claim.sessionId(),
                                new SessionError.Unknown("resume", "repeatedly interrupted"))));
                claims.release(claim.sessionId());
                continue;
            }

            journal.append(new EventDraft(claim.sessionId().value(), 1,
                    new SessionEvent.SyntheticRaised(claim.sessionId(),
                            PromptLoader.get("server-restarted"), Option.none(), Map.of())));
            // The orphaned claim is taken over by this process.
            claims.release(claim.sessionId());
            if (coordinator.wake(claim.sessionId(), ExecutionScope.INPUT)) {
                started++;
            }
        }
        return started;
    }
}
