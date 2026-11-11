package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.model.StoredEvent;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.runtime.QocEngine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads workflow run state from the engine journal.
 *
 * <p>No run table exists. {@link #load(SessionId)} replays the
 * {@code WorkflowRun*} events of one session; {@link #scan(int)} finds
 * workflow sessions by their title prefix and limits the scan.</p>
 */
public final class WorkflowRunJournal {

    private final QocEngine engine;
    private final Stores stores;

    public WorkflowRunJournal(QocEngine engine) {
        if (engine == null) throw new IllegalArgumentException("engine must not be null");
        this.engine = engine;
        this.stores = engine.stores();
    }

    public Optional<WorkflowRunSnapshot> load(String runId) {
        try {
            return load(SessionId.parse(runId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Optional<WorkflowRunSnapshot> load(SessionId session) {
        List<StoredEvent> history;
        try {
            history = stores.journal().history(session.value(), Seq.zero());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (history.isEmpty()) return Optional.empty();

        String runId = null;
        String workflowId = null;
        String definitionVersion = "";
        String contentHash = "";
        String input = "";
        String status = null;
        String currentNodeId = null;
        String activeNodeId = null;
        String error = "";
        int resumeCount = 0;
        Instant startedAt = null;
        Instant updatedAt = null;
        List<String> completedNodes = new ArrayList<>();
        Map<String, Object> nodeOutputs = new LinkedHashMap<>();
        Map<String, String> nodeSessions = new LinkedHashMap<>();
        Map<String, Object> workflowState = new LinkedHashMap<>();

        for (StoredEvent event : history) {
            if (event.time() != null) {
                if (startedAt == null) startedAt = event.time();
                updatedAt = event.time();
            }
            if (event.payload() instanceof SessionEvent.WorkflowRunStarted started) {
                runId = started.runId();
                workflowId = started.workflowId();
                definitionVersion = started.definitionVersion();
                contentHash = started.contentHash();
                input = started.input();
                workflowState.put("lastResult", started.input());
                if (started.time() != null) startedAt = started.time();
            } else if (event.payload() instanceof SessionEvent.WorkflowNodeStarted nodeStarted) {
                activeNodeId = nodeStarted.nodeId();
            } else if (event.payload() instanceof SessionEvent.WorkflowNodeCompleted completed) {
                completedNodes.add(completed.nodeId());
                Object output = completed.output() != null ? completed.output() : completed.outputText();
                nodeOutputs.put(completed.nodeId(), WorkflowValues.canonical(output));
                if (completed.childSessionId() != null && !completed.childSessionId().isBlank()) {
                    nodeSessions.put(completed.nodeId(), completed.childSessionId());
                }
                workflowState.put("lastResult", completed.outputText());
                if (completed.stateDelta() != null) {
                    for (Map.Entry<String, Object> entry : completed.stateDelta().entrySet()) {
                        workflowState.put(entry.getKey(), WorkflowValues.canonical(entry.getValue()));
                    }
                }
                currentNodeId = completed.nodeId();
                activeNodeId = null;
            } else if (event.payload() instanceof SessionEvent.WorkflowNodeFailed failed) {
                activeNodeId = failed.nodeId();
                error = failureText(failed.error());
            } else if (event.payload() instanceof SessionEvent.WorkflowRunResumed) {
                resumeCount++;
                error = "";
            } else if (event.payload() instanceof SessionEvent.WorkflowRunFinished finished) {
                status = finished.status();
                if ("COMPLETED".equalsIgnoreCase(status)) error = "";
            } else if (event.payload() instanceof SessionEvent.WorkflowRunFailed failed) {
                status = "FAILED";
                error = failureText(failed.error());
            }
        }

        if (runId == null) return Optional.empty();
        if (status == null) status = "RUNNING";
        boolean active = engine.isActive(session);
        boolean resumable = !active && !"COMPLETED".equalsIgnoreCase(status)
                && !"CANCELLED".equalsIgnoreCase(status);

        return Optional.of(new WorkflowRunSnapshot(
                runId, session.value(), workflowId, definitionVersion, contentHash, input,
                status, currentNodeId, activeNodeId,
                Collections.unmodifiableMap(new LinkedHashMap<>(nodeOutputs)),
                Collections.unmodifiableMap(new LinkedHashMap<>(nodeSessions)),
                Collections.unmodifiableMap(new LinkedHashMap<>(workflowState)),
                List.copyOf(completedNodes),
                resumeCount, error, active, resumable,
                startedAt == null ? Instant.EPOCH : startedAt,
                updatedAt == null ? Instant.EPOCH : updatedAt));
    }

    /** Lists workflow runs, newest first. */
    public List<WorkflowRunSnapshot> scan(int limit) {
        int max = Math.max(1, limit);
        List<SessionInfo> sessions;
        try {
            sessions = stores.sessions().list();
        } catch (RuntimeException e) {
            return List.of();
        }
        return sessions.stream()
                .filter(info -> info.title() != null && info.title().startsWith("workflow:"))
                .sorted(Comparator.comparing(SessionInfo::updated).reversed())
                .limit(max)
                .map(SessionInfo::id)
                .map(this::load)
                .flatMap(Optional::stream)
                .toList();
    }

    private static String failureText(de.augmentia.oc.engine.core.model.SessionError error) {
        return error == null ? "" : error.toString();
    }
}
