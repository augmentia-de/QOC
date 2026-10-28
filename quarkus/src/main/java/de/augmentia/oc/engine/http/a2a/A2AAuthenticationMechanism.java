package de.augmentia.oc.engine.http.a2a;

import de.augmentia.oc.engine.http.EngineConfig;
import de.augmentia.oc.engine.http.security.AuthMode;
import de.augmentia.oc.engine.http.security.Principal;
import de.augmentia.oc.engine.http.security.SecurityConfig;
import de.augmentia.oc.engine.http.security.TrustedProxy;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Makes A2A HTTP requests visible to the Quarkus security layer.
 *
 * <p>The A2A SDK registers plain Vert.x routes and enforces authentication on
 * its JSON-RPC handler. QOC's {@code BasicAuthFilter} and {@code
 * TrustedProxyFilter} only run on JAX-RS resources, so a Vert.x route would
 * otherwise always look anonymous. This mechanism supplies an identity for the
 * A2A paths, scoped to QOC's configured {@link AuthMode}:</p>
 *
 * <ul>
 *   <li>{@code none}: an authenticated local identity, so local use needs no
 *       credentials.</li>
 *   <li>{@code basic}: validates the {@code Authorization: Basic} header against
 *       {@code engine.auth.password}.</li>
 *   <li>{@code proxy}: accepts identity headers only from configured trusted
 *       proxy CIDRs.</li>
 *   <li>{@code oidc}: returns no identity so the Quarkus OIDC mechanism can
 *       authenticate the request.</li>
 * </ul>
 *
 * <p>Every other path is ignored so JAX-RS authentication keeps working
 * unchanged.</p>
 */
@ApplicationScoped
public class A2AAuthenticationMechanism implements HttpAuthenticationMechanism {
    private static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";
    private static final String BASIC_USER = "opencode";

    @Inject
    A2AServerConfig a2aConfig;

    @Inject
    SecurityConfig securityConfig;

    @Inject
    EngineConfig engineConfig;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (!a2aConfig.enabled() || !isA2APath(context.normalizedPath())) {
            return Uni.createFrom().nullItem();
        }
        Principal principal = switch (securityConfig.mode()) {
            case NONE -> Principal.anonymous();
            case BASIC -> basic(context);
            case PROXY -> proxy(context);
            default -> null;
        };
        if (principal == null) {
            return Uni.createFrom().nullItem();
        }
        return Uni.createFrom().item(new QuarkusSecurityIdentity.Builder()
                .setPrincipal(new QuarkusPrincipal(principal.id()))
                .addRoles(new java.util.HashSet<>(principal.roles()))
                .build());
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        if (!a2aConfig.enabled() || !isA2APath(context.normalizedPath())) {
            return Uni.createFrom().nullItem();
        }
        String challenge = securityConfig.mode() == AuthMode.OIDC || securityConfig.mode() == AuthMode.MCP_OAUTH
                ? "Bearer"
                : "Basic realm=\"QOC A2A\"";
        return Uni.createFrom().item(new ChallengeData(401, HttpHeaders.WWW_AUTHENTICATE, challenge));
    }

    private Principal basic(RoutingContext context) {
        String password = engineConfig.password();
        if (password == null || password.isBlank()) {
            return Principal.anonymous();
        }
        String header = context.request().getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0
                    || !BASIC_USER.equals(decoded.substring(0, colon))
                    || !password.equals(decoded.substring(colon + 1))) {
                return null;
            }
            return Principal.opencode();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Principal proxy(RoutingContext context) {
        String remote = context.request().getHeader("X-Forwarded-For");
        String clientIp = TrustedProxy.clientIp(remote);
        if (!TrustedProxy.isTrusted(securityConfig.trustedProxies(), clientIp)) {
            return null;
        }
        String user = firstNonBlank(
                context.request().getHeader("X-Forwarded-User"),
                context.request().getHeader("X-Remote-User"),
                context.request().getHeader("Remote-User"));
        if (user == null) {
            return null;
        }
        String email = context.request().getHeader("X-Forwarded-Email");
        return new Principal(user, user, List.of(), parseGroups(context.request().getHeader("X-Forwarded-Groups")),
                AuthMode.PROXY, "proxy", Map.of("email", email == null ? "" : email));
    }

    private static boolean isA2APath(String path) {
        if (path == null) {
            return false;
        }
        return AGENT_CARD_PATH.equals(path)
                || (path.startsWith("/.well-known/") && path.endsWith("/agent-card.json"))
                || "/".equals(path);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static List<String> parseGroups(String groupsHeader) {
        if (groupsHeader == null || groupsHeader.isBlank()) {
            return List.of();
        }
        List<String> groups = new ArrayList<>();
        for (String part : groupsHeader.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                groups.add(trimmed);
            }
        }
        return groups;
    }
}
