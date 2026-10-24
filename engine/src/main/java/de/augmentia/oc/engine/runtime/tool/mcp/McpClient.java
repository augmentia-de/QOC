package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.JsonValue;
import java.util.List;

/** One MCP server connection, reduced to the call the tool needs. */
public interface McpClient {

    JsonValue call(String toolName, JsonValue arguments);

    default List<McpRegistrar.McpToolSpec> listTools() {
        return List.of();
    }

    default List<McpResource.Resource> listResources() {
        return List.of();
    }

    default List<McpResource.Template> listResourceTemplates() {
        return List.of();
    }

    default List<McpResource.Content> readResource(String uri) {
        return List.of();
    }
}
