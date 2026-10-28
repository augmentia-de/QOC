package de.augmentia.oc.engine.http.security;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
@Priority(Priorities.AUTHENTICATION - 10)
public class TrustedProxyFilter implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(TrustedProxyFilter.class.getName());
    @Inject
    SecurityConfig securityConfig;

    @Inject
    PrincipalContext principalContext;

    @Override
    public void filter(ContainerRequestContext request) {
        AuthMode mode = securityConfig == null ? AuthMode.NONE : securityConfig.mode();
        LOG.log(Level.FINE, "trustedProxy.filter mode={0} path={1}", new Object[]{mode, request.getUriInfo().getPath()});
        if (mode != AuthMode.PROXY) {
            return;
        }
        String remote = request.getHeaderString("X-Forwarded-For");
        String clientIp = TrustedProxy.clientIp(remote);
        if (!TrustedProxy.isTrusted(securityConfig.trustedProxies(), clientIp)) {
            LOG.log(Level.FINE, "security.trusted_proxy_ignored ip={0}", clientIp);
            return;
        }
        String user = firstNonBlank(request.getHeaderString("X-Forwarded-User"),
                request.getHeaderString("X-Remote-User"),
                request.getHeaderString("Remote-User"));
        if (user == null || user.isBlank()) {
            return;
        }
        String email = request.getHeaderString("X-Forwarded-Email");
        String groupsHeader = request.getHeaderString("X-Forwarded-Groups");
        List<String> groups = parseGroups(groupsHeader);
        LOG.log(Level.FINE, "security.trusted_proxy_accepted user={0}", user);
        principalContext.set(new Principal(user, user, List.of(), groups,
                AuthMode.PROXY, "proxy", Map.of("email", email == null ? "" : email)));
    }

    private List<String> parseGroups(String groupsHeader) {
        if (groupsHeader == null || groupsHeader.isBlank()) return List.of();
        String[] parts = groupsHeader.split("[,;]");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
