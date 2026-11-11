package de.augmentia.qoc.temporal.port;

import java.util.List;
import java.util.Map;

/**
 * Port: Run-Visibility — Listing, Detail, Events, Terminate.
 */
public interface RunVisibilityPort {
    List<RunSummary> list(String query, int limit);
    RunDetail detail(String runId);
    List<RunEvent> events(String runId, int max);
    void terminate(String runId, String reason);

    record RunSummary(String runId, String status, String agent, String input, long startedAt) {}
    record RunDetail(String runId, String status, String agent, String input, String result, long startedAt, long finishedAt) {}
    record RunEvent(String type, String message, long timestamp) {}
}
