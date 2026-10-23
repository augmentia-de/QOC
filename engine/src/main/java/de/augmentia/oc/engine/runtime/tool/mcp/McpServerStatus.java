package de.augmentia.oc.engine.runtime.tool.mcp;

/**
 * Server status (tools/04-mcp.md §3). {@code NeedsAuth} is a status, not an
 * error: the server is waiting for a login.
 */
public enum McpServerStatus {
    CONNECTED, PENDING, DISABLED, FAILED, NEEDS_AUTH
}
