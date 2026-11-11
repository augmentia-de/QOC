package de.augmentia.qoc.workflowrunner.runner;

/**
 * Result of a workflow run or resume. The run ID equals the session ID: a
 * workflow run is exactly one engine session whose journal carries the run
 * state.
 */
public record WorkflowExecution(
        String runId,
        String sessionId,
        String result,
        String finish,
        boolean failed,
        boolean paused,
        String error
) {
    public WorkflowExecution {
        if (runId == null) runId = "";
        if (sessionId == null) sessionId = "";
        if (result == null) result = "";
        if (finish == null) finish = "COMPLETED";
    }
}
