package de.augmentia.oc.engine.runtime.tool.web;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.mcp.McpJsonRpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code websearch}: web search through the Firecrawl MCP endpoint.
 *
 * <p>Firecrawl can be used keyless. If {@code FIRECRAWL_API_KEY} is configured,
 * it is sent as a bearer token; otherwise the request is made without an
 * authorization header.</p>
 */
public final class FirecrawlTool {

    public static final String DEFAULT_ENDPOINT = "https://mcp.firecrawl.dev/v2/mcp";
    public static final String SEARCH_TOOL = "firecrawl_search";
    public static final String NO_RESULTS = "No search results found.";

    private final HttpTransport transport;
    private final String apiKey;
    private final String endpoint;
    private final Duration timeout;

    public FirecrawlTool(HttpTransport transport, String apiKey) {
        this(transport, apiKey, DEFAULT_ENDPOINT);
    }

    public FirecrawlTool(HttpTransport transport, String apiKey, String endpoint) {
        this(transport, apiKey, endpoint, Duration.ofSeconds(25));
    }

    public FirecrawlTool(HttpTransport transport, String apiKey, String endpoint, Duration timeout) {
        if (transport == null) throw new IllegalArgumentException("transport must not be null");
        this.transport = transport;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint;
        this.timeout = timeout == null ? Duration.ofSeconds(25) : timeout;
    }

    public static ToolDefinition definition(FirecrawlTool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        Schema input = Schema.obj(Map.of(
                "query", Schema.str(),
                "limit", Schema.opt(Schema.intg())));
        return new ToolDefinition(new ToolName("websearch"),
                "Search the web and return titles, URLs and snippets",
                input, Option.none(),
                (value, context) -> tool.execute(value),
                new ToolOptions(Option.none(), Option.of("websearch"), true, false));
    }

    private ToolOutcome execute(JsonValue value) {
        try {
            String query = JsonArgs.requiredString(value, "query");
            if (query.isBlank()) return failure("query must not be blank");
            int limit = Math.min(10, Math.max(1, JsonArgs.intOr(value, "limit", 8)));

            String body = McpJsonRpc.request(1, "tools/call", JsonValue.of(Map.of(
                    "name", JsonValue.of(SEARCH_TOOL),
                    "arguments", JsonValue.of(Map.of(
                            "query", JsonValue.of(query),
                            "limit", JsonValue.of((long) limit))))));

            try (HttpTransport.ResponseStream response = transport.postJson(
                    URI.create(endpoint), headers(), body, timeout)) {
                String text = readBody(response);
                int status = response.status();
                if (status == 401 || status == 403) {
                    return failure("Web search authentication failed");
                }
                if (status == 429) {
                    return failure("Web search rate limited");
                }
                if (status < 200 || status >= 300) {
                    return failure("Web search request failed (HTTP " + status + ")");
                }

                Result<JsonValue> rpc = McpJsonRpc.parse(text);
                if (rpc instanceof Result.Err<JsonValue> error) {
                    return failure("invalid web search response: " + error.error().message());
                }
                JsonValue rpcResponse = rpc.valueOrNull();
                Option<String> rpcError = McpJsonRpc.error(rpcResponse);
                if (rpcError.isPresent()) return failure(rpcError.orElseThrow());

                JsonValue result = McpJsonRpc.result(rpcResponse);
                if (result == null) return failure("web search returned no result");

                String content = firstTextContent(result);
                if (isError(result)) {
                    return failure(content == null || content.isBlank() ? "web search failed" : content);
                }
                if (content == null) return failure("web search returned no content");

                Result<JsonValue> parsed = Json.parse(content);
                if (parsed instanceof Result.Err<JsonValue> error) {
                    return failure("invalid search result: " + error.error().message());
                }
                return ok(format(parsed.valueOrNull()));
            }
        } catch (IllegalArgumentException e) {
            return failure(e.getMessage());
        } catch (RuntimeException e) {
            return failure(e.getMessage() == null ? "web search failed" : e.getMessage());
        }
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("User-Agent", "qoc-engine/1.0");
        if (!apiKey.isBlank()) headers.put("Authorization", "Bearer " + apiKey);
        return Map.copyOf(headers);
    }

    private static boolean isError(JsonValue result) {
        return result instanceof JsonValue.Obj object
                && object.values().get("isError") instanceof JsonValue.Bool error
                && error.value();
    }

    private static String firstTextContent(JsonValue result) {
        if (!(result instanceof JsonValue.Obj object)) return null;
        if (!(object.values().get("content") instanceof JsonValue.Arr content)) return null;
        for (JsonValue item : content.values()) {
            if (item instanceof JsonValue.Obj entry
                    && entry.values().get("type") instanceof JsonValue.Str type
                    && "text".equals(type.value())
                    && entry.values().get("text") instanceof JsonValue.Str text) {
                return text.value();
            }
        }
        return null;
    }

    private static String format(JsonValue response) {
        if (!(response instanceof JsonValue.Obj object)) return NO_RESULTS;
        if (!(object.values().get("data") instanceof JsonValue.Obj data)) return NO_RESULTS;
        if (!(data.values().get("web") instanceof JsonValue.Arr web)) return NO_RESULTS;

        List<String> entries = new ArrayList<>();
        for (JsonValue item : web.values()) {
            if (!(item instanceof JsonValue.Obj entry)) continue;
            String url = string(entry, "url");
            if (url == null || url.isBlank()) continue;
            String title = string(entry, "title");
            if (title == null || title.isBlank()) title = url;
            String description = string(entry, "description");

            StringBuilder entryText = new StringBuilder("## [").append(title).append("](").append(url).append(')');
            if (description != null && !description.isBlank()) {
                entryText.append("\n\n").append(description);
            }
            entries.add(entryText.toString());
        }
        return entries.isEmpty() ? NO_RESULTS : String.join("\n\n", entries);
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
    }

    private static String readBody(HttpTransport.ResponseStream response) {
        try {
            return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ToolOutcome ok(String text) {
        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem(text)), Map.of("provider", "firecrawl")));
    }

    private static ToolOutcome failure(String message) {
        String text = message == null || message.isBlank() ? "web search failed" : message;
        return new ToolOutcome.Failed(new SessionError.ToolExecutionError("websearch", text));
    }
}
