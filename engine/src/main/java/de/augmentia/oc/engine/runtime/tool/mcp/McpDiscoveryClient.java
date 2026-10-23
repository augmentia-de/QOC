package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.HttpTransport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Remote MCP client: JSON-RPC over HTTP with tool discovery. */
public final class McpDiscoveryClient implements McpClient {

    private final HttpTransport transport;
    private final McpServerConfig.Remote config;
    private volatile List<McpRegistrar.McpToolSpec> discovered;

    public McpDiscoveryClient(HttpTransport transport, McpServerConfig.Remote config) {
        if (transport == null || config == null) {
            throw new IllegalArgumentException("transport and config must not be null");
        }
        this.transport = transport;
        this.config = config;
    }

    @Override
    public JsonValue call(String toolName, JsonValue arguments) {
        if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("toolName must not be blank");
        if (arguments == null) arguments = JsonValue.of(Map.of());
        String body = McpJsonRpc.request(2, "tools/call", JsonValue.of(Map.of(
                "name", JsonValue.of(toolName),
                "arguments", arguments)));
        HttpTransport.ResponseStream response = transport.postJson(
                URI.create(config.url()), config.headers(), body,
                Duration.ofMillis(config.executionTimeoutMs()));
        String text = readBody(response);
        response.close();
        JsonValue parsed = McpJsonRpc.parse(text).valueOrNull();
        if (parsed == null) throw new McpFailure("empty response");
        Option<String> error = McpJsonRpc.error(parsed);
        if (error.isPresent()) throw new McpFailure(error.orElseThrow());
        JsonValue result = McpJsonRpc.result(parsed);
        return result == null ? JsonValue.of(Map.of()) : result;
    }

    @Override
    public List<McpRegistrar.McpToolSpec> listTools() {
        List<McpRegistrar.McpToolSpec> cached = discovered;
        if (cached != null) return cached;
        // Handshake first; a server that does not understand the revision fails here.
        post(McpJsonRpc.initialize());
        String body = McpJsonRpc.toolsList();
        HttpTransport.ResponseStream response = transport.postJson(
                URI.create(config.url()), config.headers(), body,
                Duration.ofMillis(config.catalogTimeoutMs()));
        String text = readBody(response);
        response.close();
        Result<JsonValue> parsed = McpJsonRpc.parse(text);
        if (parsed instanceof Result.Err<JsonValue> error) {
            throw new McpFailure("cannot parse tools/list: " + error.error().message());
        }
        Option<String> error = McpJsonRpc.error(parsed.valueOrNull());
        if (error.isPresent()) throw new McpFailure(error.orElseThrow());
        discovered = McpJsonRpc.tools(McpJsonRpc.result(parsed.valueOrNull()));
        return discovered;
    }

    public List<McpRegistrar.McpToolSpec> reload() {
        discovered = null;
        return listTools();
    }

    private void post(String body) {
        HttpTransport.ResponseStream response = transport.postJson(
                URI.create(config.url()), config.headers(), body,
                Duration.ofMillis(config.startupTimeoutMs()));
        String text = readBody(response);
        response.close();
        Result<JsonValue> parsed = McpJsonRpc.parse(text);
        if (parsed instanceof Result.Err<JsonValue>) {
            throw new McpFailure("cannot parse initialize response: " +
                    ((Result.Err<JsonValue>) parsed).error().message());
        }
        Option<String> error = McpJsonRpc.error(parsed.valueOrNull());
        if (error.isPresent()) throw new McpFailure(error.orElseThrow());
    }

    private static String readBody(HttpTransport.ResponseStream response) {
        try {
            return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
