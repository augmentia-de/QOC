package de.augmentia.oc.engine.runtime.tool.a2a;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import io.a2a.client.Client;
import io.a2a.client.ClientBuilder;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.AgentCard;
import io.a2a.spec.TransportProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

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
                AgentCard card = new A2ACardResolver(endpoint.url()).getAgentCard();
                ClientConfig config = new ClientConfig.Builder().build();
                ClientBuilder builder = Client.builder(card).clientConfig(config);
                String preferred = card.preferredTransport();
                if (preferred == null || TransportProtocol.JSONRPC.asString().equals(preferred)) {
                    builder.withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig());
                } else {
                    builder.withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig());
                }
                Client client = builder.build();
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
