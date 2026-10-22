package de.augmentia.oc.engine.core.contract.permission;

import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;

/**
 * Ephemeral permission events. They are delivered live but never logged;
 * a restart loses open requests and aborts the calling execution.
 */
public sealed interface PermissionEvent {

    record Asked(PermissionRequest request) implements PermissionEvent {
        public Asked {
            if (request == null) throw new IllegalArgumentException("request must not be null");
        }
    }

    record Replied(SessionId sessionId, PermissionRequestId requestId, Reply reply) implements PermissionEvent {
        public Replied {
            if (sessionId == null || requestId == null || reply == null) {
                throw new IllegalArgumentException("reply fields must not be null");
            }
        }
    }
}
