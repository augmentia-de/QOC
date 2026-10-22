package de.augmentia.oc.engine.runtime.execution;

/** What woke the execution. Input dominates control. */
public enum ExecutionScope {
    INPUT, CONTROL;

    public ExecutionScope merge(ExecutionScope other) {
        if (this == INPUT || other == INPUT) return INPUT;
        return CONTROL;
    }
}
