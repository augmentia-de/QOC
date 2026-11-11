package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.Journal;

import java.util.Map;

/** Writes workflow run state into the engine's session journal. */
public final class JournalWorkflowEventSink implements WorkflowEventSink {

    private final Journal journal;
    private final SessionId session;
    private final String runId;
    private final Clock clock;

    public JournalWorkflowEventSink(Journal journal, SessionId session, String runId, Clock clock) {
        if (journal == null) throw new IllegalArgumentException("journal must not be null");
        if (session == null) throw new IllegalArgumentException("session must not be null");
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId must not be blank");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.journal = journal;
        this.session = session;
        this.runId = runId;
        this.clock = clock;
    }

    @Override
    public void runStarted(String workflowId, String definitionVersion, String contentHash, String input) {
        append(new SessionEvent.WorkflowRunStarted(session, runId, workflowId,
                definitionVersion, contentHash, input, clock.now()));
    }

    @Override
    public void nodeStarted(String nodeId, int attempt) {
        append(new SessionEvent.WorkflowNodeStarted(session, runId, nodeId, attempt, clock.now()));
    }

    @Override
    public void nodeCompleted(String nodeId, String status, String outputText, Object output,
                              String childSessionId, Map<String, Object> stateDelta) {
        append(new SessionEvent.WorkflowNodeCompleted(session, runId, nodeId, status,
                outputText, output, childSessionId, stateDelta, clock.now()));
    }

    @Override
    public void nodeFailed(String nodeId, SessionError error, int attempt) {
        append(new SessionEvent.WorkflowNodeFailed(session, runId, nodeId, error, attempt, clock.now()));
    }

    @Override
    public void runResumed(int attempt) {
        append(new SessionEvent.WorkflowRunResumed(session, runId, attempt, clock.now()));
    }

    @Override
    public void runFinished(String status) {
        append(new SessionEvent.WorkflowRunFinished(session, runId, status, clock.now()));
    }

    @Override
    public void runFailed(SessionError error) {
        append(new SessionEvent.WorkflowRunFailed(session, runId, error, clock.now()));
    }

    private void append(SessionEvent event) {
        journal.append(new EventDraft(session.value(), 1, event));
    }
}
