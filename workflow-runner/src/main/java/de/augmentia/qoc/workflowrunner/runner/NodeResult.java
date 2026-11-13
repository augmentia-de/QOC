package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.model.TokenCount;

import java.util.Map;

/**
 * Result of a single workflow node execution.
 *
 * <p>The status makes pause/failure first-class results instead of
 * exceptions. Existing two- and three-argument constructors remain for
 * backward compatibility.</p>
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

    public NodeResult {
        if (text == null) text = "";
        if (structured == null) structured = Map.of();
        if (status == null) status = Status.COMPLETED;
        if (usage == null) usage = TokenCount.zero();
    }

    public String text() { return text == null ? "" : text; }

    public Map<String, Object> structured() { return structured; }

    public Object get(String key) { return structured != null ? structured.get(key) : null; }

    public static NodeResult paused(String approvalId, String approvalUrl) {
        return new NodeResult("paused", Map.of(), null, Status.PAUSED,
                approvalId == null ? "" : approvalId,
                approvalUrl == null ? "" : approvalUrl,
                TokenCount.zero());
    }

    public static NodeResult failed(String code, String message) {
        return new NodeResult(message == null ? "" : message, Map.of("code", code == null ? "" : code),
                null, Status.FAILED, null, null, TokenCount.zero());
    }
}
