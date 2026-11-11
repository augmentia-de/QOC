package de.augmentia.qoc.temporal.port;

import java.util.List;
import java.util.Map;

/**
 * Port: MCP-Client (SSE) — Tools von externen MCP-Servern laden/aufrufen.
 */
public interface McpClientPort {
    List<String> listTools(String serverUrl);
    String callTool(String serverUrl, String toolName, Map<String, Object> args);
}
