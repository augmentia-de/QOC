package de.augmentia.qoc.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Simple QOC workflow: one step that creates a session, runs a prompt, and returns the result.
 */
@WorkflowInterface
public interface QocWorkflow {

    @WorkflowMethod
    String run(String agentName, String prompt);
}
