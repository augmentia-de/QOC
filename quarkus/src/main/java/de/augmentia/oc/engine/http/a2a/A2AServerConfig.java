package de.augmentia.oc.engine.http.a2a;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Runtime configuration of the A2A server surface.
 *
 * <p>The server is disabled by default. When disabled, the HTTP routes answer
 * {@code 404} and no task can be created.</p>
 */
@ApplicationScoped
public class A2AServerConfig {
    @ConfigProperty(name = "engine.a2a.server.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "engine.a2a.server.name", defaultValue = "QOC Agent")
    String name;

    @ConfigProperty(name = "engine.a2a.server.description", defaultValue = "QOC AI Agent Runtime")
    String description;

    @ConfigProperty(name = "engine.a2a.server.version", defaultValue = "1.0.0")
    String version;

    /** Public base URL announced in the agent card, e.g. {@code https://qoc.example.com}. */
    @ConfigProperty(name = "engine.a2a.server.base-url", defaultValue = "http://localhost:8080")
    String baseUrl;

    /** QOC agent used for A2A sessions. {@code none} means the primary agent. */
    @ConfigProperty(name = "engine.a2a.server.agent", defaultValue = "none")
    String agent;

    public boolean enabled() {
        return enabled;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String version() {
        return version;
    }

    public String baseUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8080";
        }
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** Endpoint used for JSON-RPC calls. The SDK registers {@code POST /}. */
    public String endpointUrl() {
        return baseUrl() + "/";
    }

    public String agent() {
        return agent == null || agent.isBlank() || "none".equalsIgnoreCase(agent.trim())
                ? null : agent.trim();
    }
}
