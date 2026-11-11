package de.augmentia.oc.engine.http.security;

import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.Priorities;
import jakarta.annotation.Priority;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Set;

@Provider
@RequestScoped
@Priority(Priorities.AUTHENTICATION - 5)
public class OidcPrincipalProducer implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(OidcPrincipalProducer.class.getName());
    @Inject
    SecurityConfig securityConfig;

    @Inject
    PrincipalContext principalContext;

    @Inject
    jakarta.enterprise.inject.Instance<JsonWebToken> tokenInstance;

    @Override
    public void filter(ContainerRequestContext request) {
        AuthMode mode = securityConfig == null ? AuthMode.NONE : securityConfig.mode();
        if (mode != AuthMode.OIDC) {
            return;
        }
        try {
            if (tokenInstance == null || !tokenInstance.isResolvable()) {
                return;
            }
            JsonWebToken token = tokenInstance.get();
            if (token == null) {
                return;
            }
            // Prefer a human-readable username over the opaque subject UUID,
            // so workspace directories are named meaningfully (e.g. "sdlc" not a UUID).
            // Prefer a human-readable username over the opaque subject UUID,
            // so workspace directories are named meaningfully (e.g. "sdlc" not a UUID).
            Object prefClaim = token.getClaim("preferred_username");
            String id = null;
            if (prefClaim != null && !String.valueOf(prefClaim).isBlank()) id = String.valueOf(prefClaim);
            // Auth0 namespaced username claim
            if (id == null || id.isBlank()) {
                for (String cn : token.getClaimNames()) {
                    if (cn != null && cn.endsWith("/username")) {
                        Object v = token.getClaim(cn);
                        if (v != null && !String.valueOf(v).isBlank()) { id = String.valueOf(v); break; }
                    }
                }
            }
            if (id == null || id.isBlank()) {
                id = token.getSubject();
            }
            if (id == null || id.isBlank()) {
                Object email = token.getClaim("email");
                if (email != null && !String.valueOf(email).isBlank()) id = String.valueOf(email);
            }
            String name = null;
            Object prefClaim2 = token.getClaim("preferred_username");
            if (prefClaim2 != null && !String.valueOf(prefClaim2).isBlank()) name = String.valueOf(prefClaim2);
            // Auth0 namespaced username
            if (name == null || name.isBlank()) {
                for (String cn : token.getClaimNames()) {
                    if (cn != null && cn.endsWith("/username")) {
                        Object v = token.getClaim(cn);
                        if (v != null && !String.valueOf(v).isBlank()) { name = String.valueOf(v); break; }
                    }
                }
            }
            if (name == null || name.isBlank()) {
                Object nameClaim = token.getClaim("name");
                if (nameClaim != null && !String.valueOf(nameClaim).isBlank()) name = String.valueOf(nameClaim);
            }
            if (name == null || name.isBlank()) {
                Object nickname = token.getClaim("nickname");
                if (nickname != null && !String.valueOf(nickname).isBlank()) name = String.valueOf(nickname);
            }
            if (name == null || name.isBlank()) {
                Object email = token.getClaim("email");
                if (email != null && !String.valueOf(email).isBlank()) name = String.valueOf(email);
            }
            if (name == null || name.isBlank()) {
                name = id;
            }

            Set<String> roleSet = new java.util.LinkedHashSet<>();
            Object realmAccess = token.getClaim("realm_access");
            if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null && !String.valueOf(o).isBlank()) roleSet.add(stripQuotes(String.valueOf(o)));
                }
            }
            // Auth0 RBAC permissions and common role claims (including namespaced Auth0 claims).
            roleSet.addAll(stringListClaim(token, "permissions"));
            roleSet.addAll(stringListClaim(token, "roles"));
            roleSet.addAll(stringListClaim(token, "role"));
            for (String claimName : token.getClaimNames()) {
                if (claimName != null && (claimName.endsWith("/roles") || claimName.endsWith("/role"))) {
                    roleSet.addAll(stringListClaim(token, claimName));
                }
            }

            Set<String> groupSet = new java.util.LinkedHashSet<>();
            groupSet.addAll(stringListClaim(token, "groups"));
            groupSet.addAll(stringListClaim(token, "group"));
            for (String claimName : token.getClaimNames()) {
                if (claimName != null && (claimName.endsWith("/groups") || claimName.endsWith("/group"))) {
                    groupSet.addAll(stringListClaim(token, claimName));
                }
            }

            List<String> roles = new ArrayList<>(roleSet);
            List<String> groups = new ArrayList<>(groupSet);
            Map<String, Object> claims = new HashMap<>();
            Set<String> claimNames = token.getClaimNames();
            if (claimNames != null) {
                for (String claimName : claimNames) {
                    claims.put(claimName, token.getClaim(claimName));
                }
            }
            principalContext.set(new Principal(id, name, roles, groups, AuthMode.OIDC, "oidc", claims, token.getRawToken()));
            LOG.log(Level.FINE, "security.oidc_principal_set id={0} roles={1} groups={2}", new Object[]{id, roles, groups});
        } catch (Exception ignored) {
        }
    }

    private static List<String> stringListClaim(JsonWebToken token, String claimName) {
        List<String> result = new ArrayList<>();
        try {
            Object claim = token.getClaim(claimName);
            if (claim instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null && !String.valueOf(o).isBlank()) result.add(stripQuotes(String.valueOf(o)));
                }
            } else if (claim instanceof String s && !s.isBlank()) {
                for (String part : s.split(",")) {
                    if (!part.isBlank()) result.add(stripQuotes(part.trim()));
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }
}
