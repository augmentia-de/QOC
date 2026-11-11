package de.augmentia.oc.engine.http;

import java.util.List;

/**
 * Access rules for agent definitions. An agent declares the roles that may use
 * it; {@code *} is the wildcard for every principal. The check deliberately
 * lives outside the engine core so the core stays free of HTTP/security
 * dependencies.
 */
public final class AgentAccess {

    private AgentAccess() {}

    /**
     * Returns true when the given principal roles may use the agent.
     *
     * <p>{@code *} matches every caller, including an anonymous local caller.
     * An empty role list on the agent denies access; the {@link EngineConfig.AgentConfig}
     * constructor normally prevents that by applying the baseline default.</p>
     */
    public static boolean allowed(EngineConfig.AgentConfig agent, List<String> principalRoles) {
        if (agent == null) return false;
        List<String> required = agent.rolesAllowed();
        if (required == null || required.isEmpty()) return false;
        for (String role : required) {
            if (role == null || role.isBlank()) continue;
            String trimmed = role.trim();
            if ("*".equals(trimmed)) return true;
            if (hasRole(principalRoles, trimmed)) return true;
        }
        return false;
    }

    /**
     * Case-insensitive role match. {@code admin} also matches the Keycloak-style
     * {@code qoc-admin} role so both deployments behave identically.
     */
    public static boolean hasRole(List<String> principalRoles, String requiredRole) {
        if (principalRoles == null || requiredRole == null || requiredRole.isBlank()) return false;
        String wanted = requiredRole.trim();
        for (String role : principalRoles) {
            if (role == null) continue;
            String candidate = role.trim().replace("\"", "");
            if (candidate.equalsIgnoreCase(wanted)) return true;
            if ("admin".equalsIgnoreCase(wanted) && "qoc-admin".equalsIgnoreCase(candidate)) return true;
            if ("qoc-admin".equalsIgnoreCase(wanted) && "admin".equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }
}
