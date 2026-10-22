package de.augmentia.oc.engine.core.model;

import java.util.List;

/**
 * Human-in-the-loop approval request. HITL is a branch in the {@code ask}
 * step, not a separate mechanism.
 */
public record ApprovalRequest(String action, String context, List<String> reviewActions) {
    public ApprovalRequest {
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action must not be blank");
        if (context == null) context = "";
        reviewActions = reviewActions == null ? List.of() : List.copyOf(reviewActions);
    }
}
