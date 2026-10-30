package de.augmentia.qoc.decisiongate;

/** How much MCP surface the gate exposes to the agent. */
public enum McpProfile {

    /** No MCP tools are exposed. */
    NONE,

    /** Only the MCP servers named in the agent configuration. */
    SELECTED,

    /** All configured MCP servers and their tools. */
    ALL
}
