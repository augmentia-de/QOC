package de.augmentia.oc.engine.runtime.execution;

import de.augmentia.oc.engine.core.model.InterruptReason;
import de.augmentia.oc.engine.core.model.Option;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Doorbell and stop flag of one execution period. */
public final class SessionExecution {

    private final AtomicReference<ExecutionScope> doorbell = new AtomicReference<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<InterruptReason> reason = new AtomicReference<>();

    public void ring(ExecutionScope scope) {
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        doorbell.updateAndGet(current -> current == null ? scope : current.merge(scope));
    }

    public Option<ExecutionScope> consumeDoorbell() {
        return Option.of(doorbell.getAndSet(null));
    }

    public boolean doorbellSet() {
        return doorbell.get() != null;
    }

    public void stop(InterruptReason why) {
        reason.compareAndSet(null, why);
        stopped.set(true);
    }

    public boolean stopped() {
        return stopped.get();
    }

    public Option<InterruptReason> reason() {
        return Option.of(reason.get());
    }
}
