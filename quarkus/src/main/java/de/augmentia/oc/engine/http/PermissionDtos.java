package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared JSON mapping for permission requests (H7 DTO consolidation). */
final class PermissionDtos {

    private PermissionDtos() {}

    static Map<String, Object> toMap(PermissionRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", request.id().value());
        m.put("sessionId", request.sessionId().value());
        m.put("action", request.action());
        m.put("resources", request.resources());
        m.put("save", request.save());
        m.put("message", request.message());
        return m;
    }
}
