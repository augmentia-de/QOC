package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.runtime.tool.web.FirecrawlTool;
import de.augmentia.oc.engine.testing.ScriptedHttp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FirecrawlToolTest {

    private static final String RESULTS = """
            {"success":true,"data":{"web":[
              {"url":"https://effect.website","title":"Effect","description":"Effect documentation"},
              {"url":"https://example.test/no-title","description":"without title"}
            ]}}""";

    @Test
    void definitionUsesWebsearchNameAndPermission() {
        ToolDefinition definition = FirecrawlTool.definition(new FirecrawlTool(new ScriptedHttp(), ""));

        assertThat(definition.effectiveName()).isEqualTo("websearch");
        assertThat(definition.options().permission().orElseThrow()).isEqualTo("websearch");
    }

    @Test
    void keylessSearchSendsNoAuthorizationAndFormatsResults() {
        ScriptedHttp transport = new ScriptedHttp(sse(RESULTS));
        ToolDefinition definition = FirecrawlTool.definition(new FirecrawlTool(transport, ""));

        ToolOutcome outcome = execute(definition, "effect typescript");

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().content())
                .containsExactly(new ToolContent.TextItem("""
                        ## [Effect](https://effect.website)

                        Effect documentation

                        ## [https://example.test/no-title](https://example.test/no-title)

                        without title"""));
        assertThat(transport.requests()).hasSize(1);
        assertThat(transport.requests().get(0).url().toString()).isEqualTo(FirecrawlTool.DEFAULT_ENDPOINT);
        assertThat(transport.requests().get(0).headers()).doesNotContainKey("Authorization");
        assertThat(transport.requests().get(0).headers().get("Accept"))
                .isEqualTo("application/json, text/event-stream");
        assertThat(transport.requests().get(0).body())
                .contains("\"firecrawl_search\"")
                .contains("\"effect typescript\"");
    }

    @Test
    void configuredKeyIsSentAsBearer() {
        ScriptedHttp transport = new ScriptedHttp(sse(RESULTS));
        ToolDefinition definition = FirecrawlTool.definition(new FirecrawlTool(transport, "fc-secret"));

        assertThat(execute(definition, "effect")).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(transport.requests().get(0).headers().get("Authorization"))
                .isEqualTo("Bearer fc-secret");
    }

    @Test
    void emptyResultsBecomeAReadableMessage() {
        ScriptedHttp transport = new ScriptedHttp(sse("""
                {"success":true,"data":{"web":[]}}"""));
        ToolDefinition definition = FirecrawlTool.definition(new FirecrawlTool(transport, ""));

        ToolOutcome outcome = execute(definition, "nothing");

        assertThat(((ToolOutcome.Ok) outcome).result().content())
                .containsExactly(new ToolContent.TextItem(FirecrawlTool.NO_RESULTS));
    }

    @Test
    void rateLimitIsReported() {
        ScriptedHttp transport = new ScriptedHttp(ScriptedHttp.Response.text(429, "slow down"));
        ToolDefinition definition = FirecrawlTool.definition(new FirecrawlTool(transport, ""));

        assertThat(failureMessage(execute(definition, "effect"))).isEqualTo("Web search rate limited");
    }

    @Test
    void jsonRpcErrorIsReported() {
        String error = """
                {"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"unknown tool"}}""";
        ScriptedHttp transport = new ScriptedHttp(new ScriptedHttp.Response(200, Map.of(),
                error.getBytes(StandardCharsets.UTF_8)));
        ToolDefinition definition = FirecrawlTool.definition(new FirecrawlTool(transport, ""));

        assertThat(failureMessage(execute(definition, "effect"))).isEqualTo("unknown tool");
    }

    @Test
    void mcpErrorContentIsReported() {
        String error = Json.encode(JsonValue.of(Map.of(
                "jsonrpc", JsonValue.of("2.0"),
                "id", JsonValue.of(1L),
                "result", JsonValue.of(Map.of(
                        "isError", JsonValue.of(true),
                        "content", JsonValue.of(List.of(JsonValue.of(Map.of(
                                "type", JsonValue.of("text"),
                                "text", JsonValue.of("search failed"))))))))));
        ScriptedHttp transport = new ScriptedHttp(new ScriptedHttp.Response(200, Map.of(),
                error.getBytes(StandardCharsets.UTF_8)));
        ToolDefinition definition = FirecrawlTool.definition(new FirecrawlTool(transport, ""));

        assertThat(failureMessage(execute(definition, "effect"))).isEqualTo("search failed");
    }

    @Test
    void missingQueryIsReported() {
        ToolDefinition definition = FirecrawlTool.definition(new FirecrawlTool(new ScriptedHttp(), ""));

        ToolOutcome outcome = definition.execute().execute(JsonValue.of(Map.of()),
                new ToolContext(SessionId.create(), AgentId.create(),
                        MessageId.create(), CallId.create()));

        assertThat(failureMessage(outcome)).isEqualTo("missing field: query");
    }

    private static ToolOutcome execute(ToolDefinition definition, String query) {
        return definition.execute().execute(JsonValue.of(Map.of("query", JsonValue.of(query))),
                new ToolContext(SessionId.create(), AgentId.create(),
                        MessageId.create(), CallId.create()));
    }

    private static ScriptedHttp.Response sse(String searchResult) {
        String rpc = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                + "{\"type\":\"text\",\"text\":" + Json.encode(JsonValue.of(searchResult)) + "}]}}";
        String body = "event: message\ndata: " + rpc + "\n\n";
        return new ScriptedHttp.Response(200, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static String failureMessage(ToolOutcome outcome) {
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        return ((ToolOutcome.Failed) outcome).error().message();
    }
}
