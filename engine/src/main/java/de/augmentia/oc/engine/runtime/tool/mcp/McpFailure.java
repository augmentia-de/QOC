package de.augmentia.oc.engine.runtime.tool.mcp;

/** The only error family an MCP client may throw into the tool boundary. */
public final class McpFailure extends RuntimeException {

    public McpFailure(String message) {
        super(message);
    }

    public McpFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
