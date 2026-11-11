package de.augmentia.qoc.temporal.workflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Durable HITL workflow: pauses for approval, resumes via signal.
 */
@WorkflowInterface
public interface HitlWorkflow {

    @WorkflowMethod
    String run(String input);

    @SignalMethod
    void decide(boolean approved);
}
