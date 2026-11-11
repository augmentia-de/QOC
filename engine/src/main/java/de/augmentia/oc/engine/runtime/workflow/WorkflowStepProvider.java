package de.augmentia.oc.engine.runtime.workflow;

import de.augmentia.oc.engine.runtime.step.StepResult;

/**
 * Provides a single workflow step in deterministic mode.
 * Instead of calling the LLM, the QocEngine loop calls this provider
 * to get the next step (as a StepResult.Finished with tool calls).
 *
 * <p>Implemented by the workflow-runner module, which has access to
 * WorkflowDefinition and WorkflowContext.</p>
 */
public interface WorkflowStepProvider {
    /**
     * Returns the next step for the given workflow context.
     * @param stepNo current step number
     * @param workflowContext opaque workflow context (cast by implementation)
     * @return StepResult (Finished with tool calls, or Failed/Paused)
     */
    StepResult provideStep(int stepNo, Object workflowContext);
}