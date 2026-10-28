package de.augmentia.oc.engine.runtime.tool.a2a;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.AgentCard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Resolves configured A2A endpoints and turns each remote agent into a QOC tool.
 */
public final class A2AToolProvider {
    private static final Logger LOG = Logger.getLogger(A2AToolProvider.class.getName());
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

    private A2AToolProvider() {
    }

    /** Loads tools using a short-lived card cache. */
    public static List<ToolDefinition> load(List<A2AEndpoint> endpoints) {
        return load(endpoints, null);
    }

    /**
     * Loads tools for all enabled endpoints.
     *
     * @param endpoints configured endpoints, may be {@code null}
     * @param cards card cache shared with the discovery endpoint, may be {@code null}
     */
    public static List<ToolDefinition> load(List<A2AEndpoint> endpoints, A2AAgentCardCache cards) {
        List<ToolDefinition> tools = new ArrayList<>();
        if (endpoints == null) return tools;
        A2AAgentCardCache cache = cards == null ? A2AAgentCardCache.defaults(DEFAULT_CACHE_TTL) : cards;
        for (A2AEndpoint endpoint : endpoints) {
            if (endpoint == null || !endpoint.enabled()) continue;
            String egressError = A2AEgress.check(endpoint.url(), endpoint.allowedHosts(), endpoint.allowedIps(), endpoint.tlsRequired());
            if (egressError != null) {
                LOG.warning("A2A endpoint blocked by egress policy: " + endpoint.url() + " (" + egressError + ")");
                continue;
            }
            try {
                AgentCard card = cache.resolve(endpoint.url(), endpoint.headers()).card();
                ClientConfig config = new ClientConfig.Builder()
                        .setStreaming(false)
                        .setUseClientPreference(true)
                        .build();
                JSONRPCTransportConfigBuilder transportBuilder = new JSONRPCTransportConfigBuilder();
                if (!endpoint.headers().isEmpty()) {
                    // pass auth headers via interceptor is not trivial; store on endpoint for runtime use
                }
                Client client = Client.builder(card)
                        .withTransport(JSONRPCTransport.class, transportBuilder)
                        .clientConfig(config)
                        .build();
                String toolName = toolName(endpoint, card);
                tools.add(A2ATool.definition(card, client, toolName, endpoint.headers(),
                        endpoint.allowedHosts(), endpoint.allowedIps(), endpoint.tlsRequired()));
                LOG.info("Registered A2A tool " + toolName + " for " + card.name() + " @ " + endpoint.url());
            } catch (RuntimeException e) {
                LOG.warning("Failed to load A2A agent from " + endpoint.url() + ": " + e.getMessage());
            }
        }
        return tools;
    }

    /**
     * Returns the QOC tool name for an endpoint.
     *
     * <p>Uses the configured name when present, otherwise derives a stable name
     * from the agent card (or the endpoint id when the card is unavailable).</p>
     */
    public static String toolName(A2AEndpoint endpoint, AgentCard card) {
        String explicit = endpoint == null ? null : endpoint.toolName();
        if (explicit != null && !explicit.isBlank()) {
            return normalize(explicit);
        }
        String base = card == null || card.name() == null || card.name().isBlank()
                ? (endpoint == null ? "agent" : endpoint.id())
                : card.name();
        return "a2a_" + normalize(base);
    }

    public record A2AEndpoint(String id, String url, boolean enabled, String toolName,
                              Map<String, String> headers,
                              List<String> allowedHosts, List<String> allowedIps, boolean tlsRequired) {
        public A2AEndpoint {
            if (id == null) id = "";
            if (url == null) url = "";
            if (toolName == null) toolName = "";
            if (headers == null) headers = Map.of();
            else headers = Map.copyOf(headers);
            if (allowedHosts == null) allowedHosts = List.of();
            else allowedHosts = List.copyOf(allowedHosts);
            if (allowedIps == null) allowedIps = List.of();
            else allowedIps = List.copyOf(allowedIps);
        }

        public A2AEndpoint(String id, String url, boolean enabled, String toolName) {
            this(id, url, enabled, toolName, Map.of(), List.of(), List.of(), false);
        }

        public A2AEndpoint(String id, String url, boolean enabled, String toolName, Map<String, String> headers) {
            this(id, url, enabled, toolName, headers, List.of(), List.of(), false);
        }
    }

    private static String normalize(String value) {
        if (value == null) return "agent";
        return value.replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase();
    }
}
