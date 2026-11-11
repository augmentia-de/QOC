package de.augmentia.oc.engine.core.model;

import java.util.List;
import java.util.Map;

/**
 * Engine-neutral representation of an authenticated principal.
 *
 * <p>Carries identity, roles, groups, claims and an optional access token.
 * The token is kept in-memory only and must never be persisted or logged.</p>
 */
public record Principal(
        String id,
        String name,
        List<String> roles,
        List<String> groups,
        Map<String, Object> claims,
        String accessToken
) {
    public Principal {
        if (id == null || id.isBlank()) id = "anonymous";
        if (name == null || name.isBlank()) name = id;
        roles = roles == null ? List.of() : List.copyOf(roles);
        groups = groups == null ? List.of() : List.copyOf(groups);
        claims = claims == null ? Map.of() : Map.copyOf(claims);
    }

    public static Principal anonymous() {
        return new Principal("anonymous", "anonymous", List.of(), List.of(), Map.of(), null);
    }

    public static Principal localAdmin() {
        return new Principal("local", "local", List.of("admin"), List.of(), Map.of(), null);
    }

    public boolean isAdmin() {
        return roles != null && roles.stream().anyMatch(r -> "admin".equalsIgnoreCase(r));
    }
}
