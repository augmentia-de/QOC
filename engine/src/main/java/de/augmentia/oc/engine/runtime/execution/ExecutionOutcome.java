package de.augmentia.oc.engine.runtime.execution;

import de.augmentia.oc.engine.core.model.InterruptReason;
import de.augmentia.oc.engine.core.model.SessionError;

/** Terminal outcome of one execution period. */
public sealed interface ExecutionOutcome {

    record Succeeded() implements ExecutionOutcome {}

    record Failed(SessionError error) implements ExecutionOutcome {
        public Failed {
            if (error == null) throw new IllegalArgumentException("error must not be null");
        }
    }

    record Interrupted(InterruptReason reason) implements ExecutionOutcome {
        public Interrupted {
            if (reason == null) throw new IllegalArgumentException("reason must not be null");
        }
    }
}
