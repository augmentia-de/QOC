package de.augmentia.oc.engine.runtime.tool.a2a;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.spec.AgentCard;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Resolves configured A2A endpoints and turns each remote agent into a QOC tool.
 */
public final class A2AToolProvider {
    private static final Logger LOG = Logger.getLogger(A2AToolProvider.class.getName());

    private A2AToolProvider() {
    }

    public static List<ToolDefinition> load(List<A2AEndpoint> endpoints) {
        List<ToolDefinition> tools = new ArrayList<>();
        if (endpoints == null) return tools;
        for (A2AEndpoint endpoint : endpoints) {
            if (endpoint == null || !endpoint.enabled()) continue;
            try {
                AgentCard card = A2A.getAgentCard(endpoint.url());
                ClientConfig config = new ClientConfig.Builder()
                        .setStreaming(false)
                        .setUseClientPreference(true)
                        .build();
                Client client = Client.builder(card)
                        .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder())
                        .clientConfig(config)
                        .build();
                String toolName = endpoint.toolName() == null || endpoint.toolName().isBlank()
                        ? "a2a_" + normalize(card.name())
                        : normalize(endpoint.toolName());
                tools.add(A2ATool.definition(card, client, toolName));
                LOG.info("Registered A2A tool " + toolName + " for " + card.name() + " @ " + endpoint.url());
            } catch (Exception e) {
                LOG.warning("Failed to load A2A agent from " + endpoint.url() + ": " + e.getMessage());
            }
        }
        return tools;
    }

    public record A2AEndpoint(String id, String url, boolean enabled, String toolName) {
        public A2AEndpoint {
            if (id == null) id = "";
            if (url == null) url = "";
            if (toolName == null) toolName = "";
        }
    }

    private static String normalize(String value) {
        if (value == null) return "agent";
        return value.replaceAll("[^A-Za-z0-9_-]", "_").toLowerCase();
    }
}
