package de.augmentia.oc.engine.core.contract.permission;
import de.augmentia.oc.engine.core.model.*;

import java.util.List;
import java.util.Map;

public record PermissionRequest(
        PermissionRequestId id,
        SessionId sessionId,
        String action,
        List<String> resources,
        List<String> save,
        Map<String, Object> metadata,
        InvocationSource source,
        String message) {

    public PermissionRequest {
        if (id == null || sessionId == null) throw new IllegalArgumentException("request ids must not be null");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action must not be blank");
        if (resources == null || resources.isEmpty()) throw new IllegalArgumentException("resources must not be empty");
        resources = List.copyOf(resources);
        save = save == null ? List.of() : List.copyOf(save);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public record InvocationSource(String type, MessageId messageId, CallId callId) {
        public InvocationSource {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("source type must not be blank");
        }
    }
}