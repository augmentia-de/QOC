package de.augmentia.qoc.decisiongate;

/** How many tools the gate exposes to the agent. */
public enum ToolProfile {

    /** Core tools only: read, grep, glob, skill. */
    MINIMAL,

    /** The agent's configured tool set without MCP expansion. */
    STANDARD,

    /** The agent's full tool set including MCP and optional tools. */
    FULL
}
