package de.augmentia.oc.engine.http.security;

import jakarta.annotation.Priority;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Enforces {@link RolesAllowed} annotations against the current
 * {@link Principal} in {@link PrincipalContext}. This bridges the standard
 * JAX-RS security annotation to QOC's custom principal model.
 *
 * <p>When full Quarkus Security ({@code SecurityIdentity}) is adopted,
 * this filter can be removed in favour of the built-in enforcement.</p>
 */
@Provider
@Priority(Priorities.AUTHORIZATION)
public class RolesAllowedFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RolesAllowedFilter.class.getName());

    @Inject
    PrincipalContext principalContext;

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext request) {
        Method method = resourceInfo == null ? null : resourceInfo.getResourceMethod();
        if (method == null) return;

        RolesAllowed rolesAllowed = method.getAnnotation(RolesAllowed.class);
        if (rolesAllowed == null) {
            Class<?> resourceClass = resourceInfo.getResourceClass();
            if (resourceClass != null) {
                rolesAllowed = resourceClass.getAnnotation(RolesAllowed.class);
            }
        }
        if (rolesAllowed == null) return;

        List<String> required = Arrays.asList(rolesAllowed.value());
        if (required.isEmpty()) return;

        Principal current = principalContext == null ? null : principalContext.get();
        List<String> userRoles = (current == null || current.roles() == null) ? List.of() : current.roles();

        boolean allowed = required.stream().anyMatch(req -> {
            if (userRoles.contains(req)) return true;
            // Keycloak uses qoc-admin; Auth0 uses admin/qoc-admin; keep plain admin for local/basic mode.
            if ("admin".equalsIgnoreCase(req)) {
                return userRoles.stream().anyMatch(r -> "admin".equalsIgnoreCase(r)
                        || "qoc-admin".equalsIgnoreCase(r));
            }
            return false;
        });
        if (!allowed) {
            LOG.log(Level.FINE, "security.roles_denied required={0} actual={1}", new Object[]{required, userRoles});
            request.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("_tag", "Forbidden", "message",
                            "requires one of roles: " + String.join(", ", required)))
                    .build());
        }
    }
}
