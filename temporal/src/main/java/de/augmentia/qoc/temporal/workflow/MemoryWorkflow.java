package de.augmentia.qoc.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Durable memory operation: remember/recall facts per scope.
 */
@WorkflowInterface
public interface MemoryWorkflow {
    @WorkflowMethod
    String run(String op, String scope, String content);
}
