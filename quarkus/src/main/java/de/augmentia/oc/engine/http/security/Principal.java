package de.augmentia.oc.engine.http.security;

import java.util.List;
import java.util.Map;

public record Principal(
        String id,
        String name,
        List<String> roles,
        List<String> groups,
        AuthMode authMode,
        String source,
        Map<String, Object> claims
) {
    public Principal {
        roles = roles == null ? List.of() : List.copyOf(roles);
        groups = groups == null ? List.of() : List.copyOf(groups);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        if (id == null || id.isBlank()) id = "anonymous";
        if (name == null || name.isBlank()) name = id;
        if (authMode == null) authMode = AuthMode.NONE;
        if (source == null || source.isBlank()) source = authMode.name().toLowerCase();
    }

    public static Principal anonymous() {
        return new Principal("anonymous", "anonymous", List.of(), List.of(), AuthMode.NONE, "none", Map.of());
    }

    public static Principal opencode() {
        return new Principal("opencode", "opencode", List.of("admin"), List.of(), AuthMode.BASIC, "basic", Map.of());
    }

    public Map<String, Object> toMetadata() {
        return Map.of(
                "principalId", id,
                "principalName", name,
                "principalRoles", roles,
                "principalGroups", groups,
                "principalAuthMode", authMode.name(),
                "principalSource", source
        );
    }
}
