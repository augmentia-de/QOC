package de.augmentia.oc.engine.core.port.human;

import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;

/** Human-in-the-loop approval source. */
public interface ApprovalSource {

    ApprovalResult request(PermissionRequestId requestId, SessionId sessionId, String action, String context);
}
