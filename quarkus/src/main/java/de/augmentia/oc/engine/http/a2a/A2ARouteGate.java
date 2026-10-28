package de.augmentia.oc.engine.http.a2a;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Hides the A2A surface while the server is disabled.
 *
 * <p>The A2A SDK registers its Vert.x routes unconditionally when the reference
 * transport is on the classpath. This gate runs before those routes and answers
 * {@code 404} for the agent-card and JSON-RPC endpoints unless
 * {@code engine.a2a.server.enabled=true}.</p>
 */
@ApplicationScoped
public class A2ARouteGate {
    private static final String AGENT_CARD_PATH = "/.well-known/agent-card.json";

    @Inject
    A2AServerConfig config;

    void install(@Observes Router router) {
        router.route().order(-100).handler(ctx -> {
            if (config.enabled() || !isA2ARequest(ctx.request().method().name(), ctx.normalizedPath())) {
                ctx.next();
                return;
            }
            ctx.response().setStatusCode(404).end();
        });
    }

    private static boolean isA2ARequest(String method, String path) {
        if (path == null) {
            return false;
        }
        if (AGENT_CARD_PATH.equals(path)) {
            return true;
        }
        if (path.startsWith("/.well-known/") && path.endsWith("/agent-card.json")) {
            return true;
        }
        return "/".equals(path) && ("POST".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method));
    }
}
