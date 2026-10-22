package de.augmentia.oc.engine.core.contract.permission;

import de.augmentia.oc.engine.core.model.Option;

/** Result of a HITL approval. Denial may carry human feedback. */
public sealed interface ApprovalResult {

    record Approved() implements ApprovalResult {}

    record Denied(Option<String> feedback) implements ApprovalResult {
        public Denied {
            if (feedback == null) feedback = Option.none();
        }
    }
}
