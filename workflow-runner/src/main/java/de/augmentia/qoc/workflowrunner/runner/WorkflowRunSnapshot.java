package de.augmentia.qoc.workflowrunner.runner;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read model reconstructed from the engine journal. It is deliberately not
 * persisted separately: the {@code WorkflowRun*} events are the source of
 * truth and this snapshot can be rebuilt at any time.
 */
public record WorkflowRunSnapshot(
        String runId,
        String sessionId,
        String workflowId,
        String definitionVersion,
        String contentHash,
        String input,
        String status,
        String currentNodeId,
        String activeNodeId,
        Map<String, Object> nodeOutputs,
        Map<String, String> nodeSessions,
        Map<String, Object> workflowState,
        List<String> completedNodes,
        int resumeCount,
        String error,
        boolean active,
        boolean resumable,
        Instant startedAt,
        Instant updatedAt
) {
    public WorkflowRunSnapshot {
        if (runId == null) runId = "";
        if (sessionId == null) sessionId = "";
        if (workflowId == null) workflowId = "";
        if (definitionVersion == null) definitionVersion = "";
        if (contentHash == null) contentHash = "";
        if (input == null) input = "";
        if (status == null) status = "RUNNING";
        if (nodeOutputs == null) nodeOutputs = Map.of();
        if (nodeSessions == null) nodeSessions = Map.of();
        if (workflowState == null) workflowState = Map.of();
        if (completedNodes == null) completedNodes = List.of();
        if (error == null) error = "";
        if (startedAt == null) startedAt = Instant.EPOCH;
        if (updatedAt == null) updatedAt = startedAt;
    }
}
