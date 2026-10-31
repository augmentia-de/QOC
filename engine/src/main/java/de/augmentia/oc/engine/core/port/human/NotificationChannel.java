package de.augmentia.oc.engine.core.port.human;

import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;

/**
 * Outbound notification channel for human-in-the-loop approvals. Implementations
 * deliver an approval request (with a single-use token URL) to a human via
 * email, webhook, chat, etc.
 */
public interface NotificationChannel {

    /**
     * Sends an approval request to the human. The implementation must not
     * block the calling thread for an extended period; delivery failures are
     * logged but do not fail the approval flow.
     */
    void send(PermissionRequestId requestId, SessionId sessionId, String action,
              String context, String approvalUrl);
}