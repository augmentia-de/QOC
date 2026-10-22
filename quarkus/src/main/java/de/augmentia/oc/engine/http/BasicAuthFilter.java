package de.augmentia.oc.engine.http;

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
import java.util.Map;

/** HTTP Basic auth (HS: username {@code opencode}); disabled when no password is set. */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class BasicAuthFilter implements ContainerRequestFilter {

    @Inject
    EngineConfig config;

    @Override
    public void filter(ContainerRequestContext request) {
        String password = config.password();
        if (password == null || password.isBlank()) return;

        String header = request.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
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
                abort(request);
            }
        } catch (IllegalArgumentException e) {
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
