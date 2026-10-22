package de.augmentia.oc.engine.runtime.execution;

import de.augmentia.oc.engine.core.model.SessionId;

/** One pass group of an execution. Implementations must observe stop/doorbell. */
@FunctionalInterface
public interface ExecutionRunner {

    ExecutionOutcome run(SessionId session, SessionExecution execution, ExecutionScope scope);
}
