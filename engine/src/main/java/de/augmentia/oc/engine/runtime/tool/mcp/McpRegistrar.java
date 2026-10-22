package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.runtime.tool.registry.Registration;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/** Registers the tools of one MCP server under its namespace. */
public final class McpRegistrar {

    public record McpToolSpec(String name, String description, Schema inputSchema) {
        public McpToolSpec {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (inputSchema == null) throw new IllegalArgumentException("inputSchema must not be null");
        }
    }

    public List<Registration> register(ToolRegistry registry, String server, List<McpToolSpec> tools,
                                       McpClient client) {
        if (registry == null || server == null || server.isBlank() || client == null) {
            throw new IllegalArgumentException("registry, server and client must be set");
        }
        List<Registration> registrations = new ArrayList<>();
        for (McpToolSpec tool : tools == null ? List.<McpToolSpec>of() : tools) {
            ToolDefinition definition = McpTool.definition(server, tool.name(), tool.description(),
                    tool.inputSchema(), client);
            registrations.add(registry.register(definition));
        }
        return List.copyOf(registrations);
    }
}
