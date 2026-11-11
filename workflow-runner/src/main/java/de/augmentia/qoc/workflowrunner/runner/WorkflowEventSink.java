package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.model.SessionError;

import java.util.Map;

/**
 * Durable workflow event sink. Implementations append {@code WorkflowRun*}
 * events to the engine journal; the DB therefore only stores the workflow
 * specification and never a parallel run-state table.
 */
public interface WorkflowEventSink {

    void runStarted(String workflowId, String definitionVersion, String contentHash, String input);

    void nodeStarted(String nodeId, int attempt);

    void nodeCompleted(String nodeId, String status, String outputText, Object output,
                       String childSessionId, Map<String, Object> stateDelta);

    void nodeFailed(String nodeId, SessionError error, int attempt);

    void runResumed(int attempt);

    void runFinished(String status);

    void runFailed(SessionError error);

    /** No-op sink for tests and callers that do not persist workflow state. */
    static WorkflowEventSink none() {
        return new WorkflowEventSink() {
            @Override public void runStarted(String workflowId, String definitionVersion,
                                             String contentHash, String input) {}
            @Override public void nodeStarted(String nodeId, int attempt) {}
            @Override public void nodeCompleted(String nodeId, String status, String outputText,
                                                Object output, String childSessionId,
                                                Map<String, Object> stateDelta) {}
            @Override public void nodeFailed(String nodeId, SessionError error, int attempt) {}
            @Override public void runResumed(int attempt) {}
            @Override public void runFinished(String status) {}
            @Override public void runFailed(SessionError error) {}
        };
    }
}
