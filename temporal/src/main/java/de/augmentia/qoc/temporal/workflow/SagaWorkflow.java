package de.augmentia.qoc.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Durable saga: reserve → charge; if charging fails, compensate.
 */
@WorkflowInterface
public interface SagaWorkflow {
    @WorkflowMethod
    String run(String orderId, double amount);
}
