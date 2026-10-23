package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.runtime.tool.registry.Registration;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages MCP servers: connect, discover, register into the tool registry,
 * track status and reload on change. Local and remote servers are supported.
 */
public final class McpServerManager {

    private final ToolRegistry registry;
    private final HttpTransport httpTransport;
    private final ProcessRunner processRunner;
    private final Option<CredentialStore> credentials;
    private final Map<String, ServerEntry> servers = new LinkedHashMap<>();

    public McpServerManager(ToolRegistry registry, HttpTransport httpTransport, ProcessRunner processRunner) {
        this(registry, httpTransport, processRunner, Option.none());
    }

    public McpServerManager(ToolRegistry registry, HttpTransport httpTransport, ProcessRunner processRunner,
                            Option<CredentialStore> credentials) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.registry = registry;
        this.httpTransport = httpTransport;
        this.processRunner = processRunner;
        this.credentials = credentials == null ? Option.none() : credentials;
    }

    public void add(McpServerConfig config) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        ServerEntry entry = new ServerEntry(config);
        servers.put(config.name(), entry);
        if (config.disabled()) {
            entry.status = McpServerStatus.DISABLED;
            return;
        }
        discover(entry);
    }

    public McpServerStatus status(String name) {
        ServerEntry entry = servers.get(name);
        return entry == null ? McpServerStatus.DISABLED : entry.status;
    }

    public List<String> serverNames() {
        return List.copyOf(servers.keySet());
    }

    public List<McpRegistrar.McpToolSpec> discovered(String name) {
        ServerEntry entry = servers.get(name);
        return entry == null ? List.of() : List.copyOf(entry.tools);
    }

    public Map<String, List<McpRegistrar.McpToolSpec>> discoveredAll() {
        Map<String, List<McpRegistrar.McpToolSpec>> all = new LinkedHashMap<>();
        for (Map.Entry<String, ServerEntry> entry : servers.entrySet()) {
            if (!entry.getValue().tools.isEmpty()) {
                all.put(entry.getKey(), List.copyOf(entry.getValue().tools));
            }
        }
        return all;
    }

    public McpClient client(String name) {
        ServerEntry entry = servers.get(name);
        return entry == null ? null : entry.client;
    }

    public void authenticate(String name, String code, String redirectUri, String clientId, String clientSecret) {
        McpClient client = client(name);
        if (!(client instanceof McpDiscoveryClient discovery)) {
            throw new McpFailure("server is not a remote discovery client: " + name);
        }
        discovery.authenticate(code, redirectUri, null, clientId, clientSecret);
    }

    public String error(String name) {
        ServerEntry entry = servers.get(name);
        return entry == null ? null : entry.error;
    }

    public void discoverAll() {
        for (ServerEntry entry : servers.values()) {
            if (entry.config.disabled()) continue;
            discover(entry);
        }
    }

    private void discover(ServerEntry entry) {
        entry.status = McpServerStatus.PENDING;
        McpClient client = client(entry.config);
        if (client == null) {
            entry.status = McpServerStatus.FAILED;
            entry.error = "no transport for server type";
            return;
        }
        try {
            List<McpRegistrar.McpToolSpec> tools = client.listTools();
            entry.tools = List.copyOf(tools);
            entry.client = client;
            entry.status = McpServerStatus.CONNECTED;
            entry.error = null;
            register(entry, tools);
        } catch (RuntimeException e) {
            entry.status = McpServerStatus.FAILED;
            entry.error = e.getMessage() == null ? "discovery failed" : e.getMessage();
            entry.tools = List.of();
        }
    }

    public void reload(String name) {
        ServerEntry entry = servers.get(name);
        if (entry == null) return;
        // Close previous registrations so the registry re-exposes the predecessor.
        for (Registration registration : entry.registrations) registration.close();
        entry.registrations.clear();
        discover(entry);
    }

    private McpClient client(McpServerConfig config) {
        return switch (config) {
            case McpServerConfig.Remote remote -> new McpDiscoveryClient(httpTransport, remote,
                    credentials, "mcp:" + remote.name());
            case McpServerConfig.Local local -> processRunner == null ? null
                    : new McpStdioClient(processRunner, local);
        };
    }

    private void register(ServerEntry entry, List<McpRegistrar.McpToolSpec> tools) {
        if (tools.isEmpty()) return;
        McpClient client = entry.client;
        List<Registration> registrations = new McpRegistrar().register(
                registry, entry.config.name(), tools, client);
        entry.registrations.addAll(registrations);
    }

    private static final class ServerEntry {
        final McpServerConfig config;
        McpServerStatus status = McpServerStatus.PENDING;
        String error;
        McpClient client;
        List<McpRegistrar.McpToolSpec> tools = List.of();
        final List<Registration> registrations = new ArrayList<>();

        ServerEntry(McpServerConfig config) {
            this.config = config;
        }
    }
}
