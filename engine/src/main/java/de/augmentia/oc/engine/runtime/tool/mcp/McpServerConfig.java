package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.JsonValue;

import java.util.List;
import java.util.Map;

/**
 * MCP server configuration. Local servers run as a process; remote servers are
 * reached over HTTP. OAuth is optional for remote servers.
 */
public sealed interface McpServerConfig {

    String name();

    boolean disabled();

    boolean codemode();

    record Local(String name, List<String> command, String cwd, Map<String, String> environment,
                 boolean disabled, boolean codemode, int startupTimeoutMs,
                 int catalogTimeoutMs, int executionTimeoutMs) implements McpServerConfig {
        public Local {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            command = command == null ? List.of() : List.copyOf(command);
            if (cwd == null) cwd = "";
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            if (startupTimeoutMs <= 0) startupTimeoutMs = 10_000;
            if (catalogTimeoutMs <= 0) catalogTimeoutMs = 10_000;
            if (executionTimeoutMs <= 0) executionTimeoutMs = 30_000;
        }
    }

    record Remote(String name, String url, Map<String, String> headers, boolean oauth,
                  boolean disabled, boolean codemode, int startupTimeoutMs,
                  int catalogTimeoutMs, int executionTimeoutMs) implements McpServerConfig {
        public Remote {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (url == null || url.isBlank()) throw new IllegalArgumentException("url must not be blank");
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            if (startupTimeoutMs <= 0) startupTimeoutMs = 10_000;
            if (catalogTimeoutMs <= 0) catalogTimeoutMs = 10_000;
            if (executionTimeoutMs <= 0) executionTimeoutMs = 30_000;
        }
    }
}
