package de.augmentia.qoc.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Durable run with metrics collection.
 */
@WorkflowInterface
public interface MetricsWorkflow {
    @WorkflowMethod
    String run(String input);
}
