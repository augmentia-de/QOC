package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.model.TokenCount;

import java.util.Map;

/**
 * Result of a single workflow node execution.
 *
 * <p>The status makes pause/failure first-class results instead of
 * exceptions. Existing two- and three-argument constructors remain for
 * backward compatibility. Pause can be signalled either via
 * {@link Status#PAUSED} or via {@link #pauseRequested()} (both views are
 * kept in sync so the two calling conventions from the merged branches
 * agree).</p>
 */
public record NodeResult(
        String text,
        Map<String, Object> structured,
        String sessionId,
        Status status,
        String approvalId,
        String approvalUrl,
        TokenCount usage
) {
    public enum Status { COMPLETED, PAUSED, FAILED }

    public NodeResult(String text, Map<String, Object> structured) {
        this(text, structured, null, Status.COMPLETED, null, null, TokenCount.zero());
    }

    public NodeResult(String text, Map<String, Object> structured, String sessionId) {
        this(text, structured, sessionId, Status.COMPLETED, null, null, TokenCount.zero());
    }

    /** Compatibility constructor from the workspace branch (pauseRequested flag). */
    public NodeResult(String text, Map<String, Object> structured, String sessionId,
                      boolean pauseRequested, String approvalId) {
        this(text, structured, sessionId,
                pauseRequested ? Status.PAUSED : Status.COMPLETED,
                approvalId, null, TokenCount.zero());
    }

    public NodeResult {
        if (text == null) text = "";
        if (structured == null) structured = Map.of();
        if (status == null) status = Status.COMPLETED;
        if (approvalId == null) approvalId = "";
        if (approvalUrl == null) approvalUrl = "";
        if (usage == null) usage = TokenCount.zero();
    }

    public String text() { return text == null ? "" : text; }

    public Map<String, Object> structured() { return structured; }

    public Object get(String key) { return structured != null ? structured.get(key) : null; }

    /** Derived view kept in sync with {@link Status#PAUSED}. */
    public boolean pauseRequested() { return status == Status.PAUSED; }

    public static NodeResult paused(String approvalId, String approvalUrl) {
        return new NodeResult("paused", Map.of(), null, Status.PAUSED,
                approvalId == null ? "" : approvalId,
                approvalUrl == null ? "" : approvalUrl,
                TokenCount.zero());
    }

    /** Workspace-style factory: pause carrying structured output. */
    public static NodeResult paused(String approvalId, Map<String, Object> structured) {
        return new NodeResult("PAUSED", structured == null ? Map.of() : structured, null,
                Status.PAUSED, approvalId == null ? "" : approvalId, "", TokenCount.zero());
    }

    public static NodeResult failed(String code, String message) {
        return new NodeResult(message == null ? "" : message, Map.of("code", code == null ? "" : code),
                null, Status.FAILED, null, null, TokenCount.zero());
    }
}
