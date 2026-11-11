package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.adapter.net.JdkHttpTransport;
import de.augmentia.oc.engine.adapter.process.JdkProcessRunner;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.runtime.tool.mcp.McpResourceTools;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;

import org.jboss.logging.Logger;

import java.util.List;

/**
 * Owns MCP server discovery and A2A tool registration. Extracted from
 * {@link Engine} as part of H6.
 */
public final class McpRegistry {

    private static final Logger LOG = Logger.getLogger(McpRegistry.class.getName());

    private final McpServerManager mcp;

    public McpRegistry(ToolRegistry registry, JdkHttpTransport http,
                       JdkProcessRunner processRunner,
                       Option<CredentialStore> credentialStore) {
        this.mcp = new McpServerManager(registry, http, processRunner, credentialStore);
    }

    public void setup(ToolRegistry registry, EngineConfig config,
                      A2AConfig a2aConfig) {
        for (McpServerConfig server : config.mcpServers()) {
            mcp.add(server);
        }
        registry.register(McpResourceTools.list(mcp));
        registry.register(McpResourceTools.read(mcp));
        try {
            List<de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.A2AEndpoint> endpoints =
                    a2aConfig == null ? List.of() : a2aConfig.endpoints();
            de.augmentia.oc.engine.runtime.tool.a2a.A2AAgentCardCache cards =
                    a2aConfig == null ? null : a2aConfig.cards();
            for (de.augmentia.oc.engine.core.contract.tool.ToolDefinition a2aTool :
                    de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.load(endpoints, cards)) {
                registry.register(a2aTool);
            }
        } catch (RuntimeException e) {
            LOG.warnf("A2A tool registration failed: %s", e.getMessage());
        }
    }

    public McpServerManager manager() {
        return mcp;
    }

    public List<String> serverNames() {
        return mcp.serverNames();
    }

    public String status(String name) {
        return mcp.status(name).name();
    }

    public String error(String name) {
        return mcp.error(name);
    }

    public String authorizationUrl(String name, String clientId, String redirectUri) {
        String effectiveClient = clientId == null || clientId.isBlank() ? "opencode" : clientId;
        String effectiveRedirect = redirectUri == null || redirectUri.isBlank()
                ? "http://127.0.0.1:8080/api/mcp/" + name + "/callback"
                : redirectUri;
        return mcp.client(name) instanceof de.augmentia.oc.engine.runtime.tool.mcp.McpDiscoveryClient discovery
                ? discovery.authorizationUrl(effectiveClient, effectiveRedirect, "state-" + System.currentTimeMillis())
                : null;
    }

    public void authenticate(String name, String code, String redirectUri, String clientId, String clientSecret) {
        String effectiveClient = clientId == null || clientId.isBlank() ? "opencode" : clientId;
        String effectiveRedirect = redirectUri == null || redirectUri.isBlank()
                ? "http://127.0.0.1:8080/api/mcp/" + name + "/callback"
                : redirectUri;
        mcp.authenticate(name, code, effectiveRedirect, effectiveClient,
                clientSecret == null ? "" : clientSecret);
        mcp.reload(name);
    }
}
