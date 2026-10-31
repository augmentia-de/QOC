package de.augmentia.oc.engine.core.model;


/** Result of a HITL approval. Denial may carry human feedback. */
public sealed interface ApprovalResult {

    record Approved() implements ApprovalResult {}

    record Denied(Option<String> feedback) implements ApprovalResult {
        public Denied {
            if (feedback == null) feedback = Option.none();
        }
    }

    /** Approval is pending; the session is suspended until a decision is made. */
    record Paused(String approvalId, String approvalUrl) implements ApprovalResult {
        public Paused {
            if (approvalId == null || approvalId.isBlank()) throw new IllegalArgumentException("approvalId must not be blank");
            if (approvalUrl == null) approvalUrl = "";
        }
    }
}
