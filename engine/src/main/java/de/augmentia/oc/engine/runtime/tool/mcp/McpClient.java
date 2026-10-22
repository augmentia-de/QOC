package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.JsonValue;

/** One MCP server connection, reduced to the call the tool needs. */
public interface McpClient {

    JsonValue call(String toolName, JsonValue arguments);
}
