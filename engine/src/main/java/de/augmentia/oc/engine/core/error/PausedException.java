package de.augmentia.oc.engine.core.error;

/**
 * Thrown when a tool call is paused pending human approval (async approval).
 * Carries the approval id and the approval URL.
 */
public class PausedException extends RuntimeException {
    private final String approvalId;
    private final String approvalUrl;

    public PausedException(String approvalId, String approvalUrl) {
        super("approval pending: " + approvalId);
        this.approvalId = approvalId;
        this.approvalUrl = approvalUrl == null ? "" : approvalUrl;
    }

    public String approvalId() { return approvalId; }
    public String approvalUrl() { return approvalUrl; }
}