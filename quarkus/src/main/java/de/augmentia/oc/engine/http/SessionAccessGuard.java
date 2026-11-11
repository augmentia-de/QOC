package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.http.security.PrincipalContext;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Central session/principal access checks shared by the REST resources.
 *
 * <p>Extracted from {@link HttpApi} as part of H7 so that every resource uses
 * the same ownership semantics. Admins may access all sessions; regular users
 * only their own.</p>
 */
@RequestScoped
public class SessionAccessGuard {

    private static final Logger LOG = Logger.getLogger(SessionAccessGuard.class.getName());

    @Inject
    Engine engine;

    @Inject
    PrincipalContext principalContext;

    /** Returns the current principal id, or {@code null} when unauthenticated. */
    public String currentUser() {
        if (principalContext == null) return null;
        var principal = principalContext.get();
        return principal == null ? null : principal.id();
    }

    /** Returns the current principal's roles, never {@code null}. */
    public List<String> currentRoles() {
        if (principalContext == null) return List.of();
        var principal = principalContext.get();
        if (principal == null || principal.roles() == null) return List.of();
        return principal.roles();
    }

    /** Returns true if the current principal has the given role (admin/qoc-admin alias aware). */
    public boolean hasRole(String role) {
        if (role == null || role.isBlank()) return true;
        return AgentAccess.hasRole(currentRoles(), role);
    }

    /** Returns true when the current principal is an administrator. */
    public boolean isAdmin() {
        return hasRole("admin");
    }

    /** Returns a 403 response when the current principal may not use the named agent. */
    public Response requireAgent(String name) {
        if (engine.agentAllowed(name, currentRoles())) return null;
        return Response.status(Response.Status.FORBIDDEN)
                .entity(Map.of("_tag", "Forbidden", "message", "agent not allowed: " + name))
                .build();
    }

    /** Returns true when the given session belongs to the current principal or they are admin. */
    public boolean isOwner(SessionInfo info, String current) {
        if (info == null) return false;
        if (isAdmin()) return true;
        if (current == null || current.isBlank()) return false;
        return engine.isOwner(info, current);
    }

    /**
     * Returns a 403 response when the current principal may not access the
     * session, otherwise {@code null}. Unknown sessions and malformed ids are
     * left to the handler so it can produce the appropriate 404/400.
     */
    public Response requireAccess(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        try {
            var info = engine.session(SessionId.parse(sessionId));
            if (info.isEmpty()) return null;
            String current = currentUser();
            if (!isOwner(info.orElseThrow(), current)) {
                LOG.warning("session access denied session=" + sessionId + " user=" + current);
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("_tag", "Forbidden", "message", "not session owner"))
                        .build();
            }
        } catch (IllegalArgumentException ignored) {
            // invalid id: handler returns 400
        }
        return null;
    }
}
