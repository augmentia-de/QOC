package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * MCP client over stdio: the server runs as a child process and speaks
 * JSON-RPC 2.0 over newline-delimited messages.
 */
public final class McpStdioClient implements McpClient {

    private final ProcessRunner runner;
    private final McpServerConfig.Local config;
    private volatile ProcessRunner.Handle handle;
    private int requestCounter;
    private int returnedLines;

    public McpStdioClient(ProcessRunner runner, McpServerConfig.Local config) {
        if (runner == null || config == null) throw new IllegalArgumentException("dependencies must not be null");
        this.runner = runner;
        this.config = config;
    }

    @Override
    public JsonValue call(String toolName, JsonValue arguments) {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName must not be blank");
        if (arguments == null) arguments = JsonValue.of(Map.of());
        ensureStarted();
        String body = McpJsonRpc.request(++requestCounter, "tools/call", JsonValue.of(Map.of(
                "name", JsonValue.of(toolName),
                "arguments", arguments)));
        String response = exchange(body);
        Result<JsonValue> parsed = McpJsonRpc.parse(response);
        if (parsed instanceof Result.Err<JsonValue> error) throw new McpFailure(error.error().message());
        Option<String> error = McpJsonRpc.error(parsed.valueOrNull());
        if (error.isPresent()) throw new McpFailure(error.orElseThrow());
        JsonValue result = McpJsonRpc.result(parsed.valueOrNull());
        return result == null ? JsonValue.of(Map.of()) : result;
    }

    @Override
    public List<McpRegistrar.McpToolSpec> listTools() {
        ensureStarted();
        exchange(McpJsonRpc.initialize());
        String response = exchange(McpJsonRpc.toolsList());
        Result<JsonValue> parsed = McpJsonRpc.parse(response);
        if (parsed instanceof Result.Err<JsonValue> error) throw new McpFailure(error.error().message());
        Option<String> error = McpJsonRpc.error(parsed.valueOrNull());
        if (error.isPresent()) throw new McpFailure(error.orElseThrow());
        return McpJsonRpc.tools(McpJsonRpc.result(parsed.valueOrNull()));
    }

    private void ensureStarted() {
        if (handle != null) return;
        ProcessRunner.ProcessSpec spec = new ProcessRunner.ProcessSpec(
                config.command(), Option.of(Path.of(config.cwd().isBlank() ? "." : config.cwd())),
                config.environment(), Option.none(), false);
        handle = runner.start(spec);
    }

    private String exchange(String request) {
        handle.write(request + "\n");
        long deadline = System.currentTimeMillis() + config.executionTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            String output = handle.output();
            if (output != null && !output.isEmpty()) {
                String[] lines = output.split("\n", -1);
                for (int i = returnedLines; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.startsWith("{")) {
                        returnedLines = i + 1;
                        return line;
                    }
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpFailure("interrupted");
            }
        }
        throw new McpFailure("timeout waiting for response");
    }

    private static java.nio.file.Path Path(String value) {
        return java.nio.file.Path.of(value);
    }
}
