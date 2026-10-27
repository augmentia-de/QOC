package de.augmentia.oc.engine.http.security;

import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
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
            String id = token.getSubject();
            if (id == null || id.isBlank()) {
                Object preferred = token.getClaim("preferred_username");
                if (preferred != null) id = String.valueOf(preferred);
            }
            String name = null;
            Object preferred = token.getClaim("preferred_username");
            if (preferred != null) name = String.valueOf(preferred);
            if (name == null || name.isBlank()) {
                Object nameClaim = token.getClaim("name");
                if (nameClaim != null) name = String.valueOf(nameClaim);
            }
            if (name == null || name.isBlank()) {
                name = id;
            }
            List<String> roles = new ArrayList<>();
            Object realmAccess = token.getClaim("realm_access");
            if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) roles.add(String.valueOf(o));
                }
            }
            List<String> groups = new ArrayList<>();
            Object groupsClaim = token.getClaim("groups");
            if (groupsClaim instanceof List<?> list) {
                for (Object o : list) {
                    if (o != null) groups.add(String.valueOf(o));
                }
            }
            Map<String, Object> claims = new HashMap<>();
            Set<String> claimNames = token.getClaimNames();
            if (claimNames != null) {
                for (String claimName : claimNames) {
                    claims.put(claimName, token.getClaim(claimName));
                }
            }
            principalContext.set(new Principal(id, name, roles, groups, AuthMode.OIDC, "oidc", claims));
            LOG.log(Level.FINE, "security.oidc_principal_set id={0}", id);
        } catch (Exception ignored) {
        }
    }
}
