package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.http.security.AuthMode;
import de.augmentia.oc.engine.http.security.Principal;
import de.augmentia.oc.engine.http.security.PrincipalContext;
import de.augmentia.oc.engine.http.security.SecurityConfig;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/** HTTP Basic auth (HS: username {@code opencode}); disabled when no password/mode requires it. */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(BasicAuthFilter.class.getName());

    @Inject
    EngineConfig config;

    @Inject
    SecurityConfig securityConfig;

    @Inject
    PrincipalContext principalContext;

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if ("health/live".equals(path) || "health/ready".equals(path)) return;
        if ("metrics".equals(path) || "audit".equals(path)) return;

        AuthMode mode = securityConfig == null ? AuthMode.NONE : securityConfig.mode();
        if (mode == AuthMode.NONE) {
            String password = config.password();
            if (password == null || password.isBlank()) {
                principalContext.set(Principal.anonymous());
                return;
            }
            mode = AuthMode.BASIC;
        }
        if (mode != AuthMode.BASIC) {
            if (!securityConfig.authenticationRequired() && (config.password() == null || config.password().isBlank())) {
                principalContext.set(Principal.anonymous());
                return;
            }
            if (mode != AuthMode.BASIC) {
                // OIDC/proxy/mcp-oauth handled by dedicated filters/providers later
                if (principalContext.get() == null || "anonymous".equals(principalContext.get().id())) {
                    principalContext.set(new Principal("unknown", "unknown", List.of(), List.of(), mode, mode.name().toLowerCase(), Map.of()));
                }
                return;
            }
        }

        String password = config.password();
        if (password == null || password.isBlank()) {
            principalContext.set(Principal.anonymous());
            return;
        }

        String header = request.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            principalContext.set(Principal.anonymous());
            LOG.log(Level.WARNING, "security.basic_auth_failed path={0}", request.getUriInfo().getPath());
            abort(request);
            return;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0
                    || !"opencode".equals(decoded.substring(0, colon))
                    || !password.equals(decoded.substring(colon + 1))) {
                principalContext.set(Principal.anonymous());
                abort(request);
                return;
            }
            principalContext.set(Principal.opencode());
            LOG.log(Level.FINE, "security.basic_auth_success");
        } catch (IllegalArgumentException e) {
            principalContext.set(Principal.anonymous());
            abort(request);
        }
    }

    private static void abort(ContainerRequestContext request) {
        request.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Secure Area\"")
                .entity(Map.of("_tag", "UnauthorizedError", "message", "authentication required"))
                .build());
    }
}
