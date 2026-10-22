package de.augmentia.oc.engine.core.contract.permission;
import de.augmentia.oc.engine.core.model.*;

import java.util.List;
import java.util.Map;

public record PermissionEvaluation(
        SessionId sessionId,
        Option<AgentId> agent,
        String action,
        List<String> resources,
        Map<String, Object> metadata,
        Effect effect) {

    public PermissionEvaluation {
        if (sessionId == null) throw new IllegalArgumentException("sessionId must not be null");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action must not be blank");
        resources = resources == null ? List.of() : List.copyOf(resources);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (effect == null) throw new IllegalArgumentException("effect must not be null");
    }
}