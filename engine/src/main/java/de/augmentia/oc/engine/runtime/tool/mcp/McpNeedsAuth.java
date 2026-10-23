package de.augmentia.oc.engine.runtime.tool.mcp;

/** Raised when a remote MCP server requires OAuth login. */
public final class McpNeedsAuth extends McpFailure {
    public McpNeedsAuth(String message) {
        super(message);
    }
}
