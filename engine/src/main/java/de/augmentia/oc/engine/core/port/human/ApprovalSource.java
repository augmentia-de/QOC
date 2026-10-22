package de.augmentia.oc.engine.core.port.human;

import de.augmentia.oc.engine.core.model.ApprovalResult;

/** Human-in-the-loop approval source. */
public interface ApprovalSource {

    ApprovalResult request(String action, String context);
}
