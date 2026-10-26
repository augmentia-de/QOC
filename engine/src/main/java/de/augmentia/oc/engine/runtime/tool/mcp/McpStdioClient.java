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

    @Override
    public List<McpResource.Resource> listResources() {
        JsonValue result = rpc("resources/list", JsonValue.of(Map.of()));
        List<McpResource.Resource> resources = new java.util.ArrayList<>();
        if (result instanceof JsonValue.Obj object
                && object.values().get("resources") instanceof JsonValue.Arr array) {
            for (JsonValue item : array.values()) {
                if (item instanceof JsonValue.Obj resource) {
                    resources.add(new McpResource.Resource(config.name(), string(resource, "uri"),
                            string(resource, "name"), string(resource, "description"),
                            string(resource, "mimeType")));
                }
            }
        }
        return List.copyOf(resources);
    }

    @Override
    public List<McpResource.Template> listResourceTemplates() {
        JsonValue result = rpc("resources/templates/list", JsonValue.of(Map.of()));
        List<McpResource.Template> templates = new java.util.ArrayList<>();
        if (result instanceof JsonValue.Obj object
                && object.values().get("resourceTemplates") instanceof JsonValue.Arr array) {
            for (JsonValue item : array.values()) {
                if (item instanceof JsonValue.Obj template) {
                    templates.add(new McpResource.Template(config.name(), string(template, "uriTemplate"),
                            string(template, "name"), string(template, "description"),
                            string(template, "mimeType")));
                }
            }
        }
        return List.copyOf(templates);
    }

    @Override
    public List<McpResource.Content> readResource(String uri) {
        if (uri == null || uri.isBlank()) throw new IllegalArgumentException("uri must not be blank");
        JsonValue result = rpc("resources/read", JsonValue.of(Map.of("uri", JsonValue.of(uri))));
        List<McpResource.Content> contents = new java.util.ArrayList<>();
        if (result instanceof JsonValue.Obj object
                && object.values().get("contents") instanceof JsonValue.Arr array) {
            for (JsonValue item : array.values()) {
                if (item instanceof JsonValue.Obj content) {
                    contents.add(new McpResource.Content(string(content, "uri"),
                            string(content, "mimeType"), string(content, "text"),
                            string(content, "blob")));
                }
            }
        }
        return List.copyOf(contents);
    }

    private JsonValue rpc(String method, JsonValue params) {
        if (handle == null) listTools();
        String response = exchange(McpJsonRpc.request(++requestCounter, method, params));
        Result<JsonValue> parsed = McpJsonRpc.parse(response);
        if (parsed instanceof Result.Err<JsonValue> error) throw new McpFailure(error.error().message());
        Option<String> error = McpJsonRpc.error(parsed.valueOrNull());
        if (error.isPresent()) throw new McpFailure(error.orElseThrow());
        JsonValue result = McpJsonRpc.result(parsed.valueOrNull());
        return result == null ? JsonValue.of(Map.of()) : result;
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
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

}
