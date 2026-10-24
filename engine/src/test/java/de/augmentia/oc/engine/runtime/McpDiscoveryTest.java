package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.runtime.tool.mcp.McpDiscoveryClient;
import de.augmentia.oc.engine.runtime.tool.mcp.McpFailure;
import de.augmentia.oc.engine.runtime.tool.mcp.McpStdioClient;
import de.augmentia.oc.engine.runtime.tool.mcp.McpInstructions;
import de.augmentia.oc.engine.runtime.tool.mcp.McpRegistrar;
import de.augmentia.oc.engine.runtime.tool.mcp.McpResource;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerStatus;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.testing.FakeProcess;
import de.augmentia.oc.engine.testing.ScriptedHttp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpDiscoveryTest {

    private static final String INITIALIZE_RESPONSE = """
            {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}""";
    private static final String TOOLS_RESPONSE = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[
              {"name":"search","description":"search code","inputSchema":{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}},
              {"name":"issue","description":"create issue","inputSchema":{"type":"object"}}
            ]}}""";
    private static final String CALL_RESPONSE = """
            {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"found"}]}}""";

    @Test
    void remoteDiscoveryReturnsToolSpecs() {
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), INITIALIZE_RESPONSE.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), TOOLS_RESPONSE.getBytes()));
        McpDiscoveryClient client = new McpDiscoveryClient(transport,
                new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                        false, false, true, 5_000, 5_000, 5_000));

        assertThat(client.listTools()).extracting(McpRegistrar.McpToolSpec::name)
                .containsExactly("search", "issue");
    }

    @Test
    void remoteCallReturnsTheResult() {
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), CALL_RESPONSE.getBytes()));
        McpDiscoveryClient client = new McpDiscoveryClient(transport,
                new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                        false, false, true, 5_000, 5_000, 5_000));

        JsonValue result = client.call("search", JsonValue.of(Map.of("q", JsonValue.of("x"))));
        assertThat(result).isInstanceOf(JsonValue.Obj.class);
    }

    @Test
    void remoteListsResourcesAndTemplates() {
        String resources = """
                {"jsonrpc":"2.0","id":3,"result":{"resources":[
                  {"uri":"file:///a","name":"a","description":"d","mimeType":"text/plain"}]}}""";
        String templates = """
                {"jsonrpc":"2.0","id":3,"result":{"resourceTemplates":[
                  {"uriTemplate":"file:///{x}","name":"t","description":"d","mimeType":"text/plain"}]}}""";
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), INITIALIZE_RESPONSE.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), TOOLS_RESPONSE.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), resources.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), templates.getBytes()));
        McpDiscoveryClient client = new McpDiscoveryClient(transport,
                new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                        false, false, true, 5_000, 5_000, 5_000));

        assertThat(client.listResources()).extracting(McpResource.Resource::uri)
                .containsExactly("file:///a");
        assertThat(client.listResourceTemplates()).extracting(McpResource.Template::uriTemplate)
                .containsExactly("file:///{x}");
    }

    @Test
    void remoteReadsResourceContent() {
        String read = """
                {"jsonrpc":"2.0","id":3,"result":{"contents":[
                  {"uri":"file:///a","mimeType":"text/plain","text":"hello"}]}}""";
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), INITIALIZE_RESPONSE.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), TOOLS_RESPONSE.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), read.getBytes()));
        McpDiscoveryClient client = new McpDiscoveryClient(transport,
                new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                        false, false, true, 5_000, 5_000, 5_000));

        assertThat(client.readResource("file:///a")).extracting(McpResource.Content::text)
                .containsExactly("hello");
    }

    @Test
    void remoteCallHandlesSseResponses() {
        String callSse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"found\"}]}}";
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), callSse.getBytes()));
        McpDiscoveryClient client = new McpDiscoveryClient(transport,
                new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                        false, false, true, 5_000, 5_000, 5_000));

        JsonValue result = client.call("search", JsonValue.of(Map.of("q", JsonValue.of("x"))));
        assertThat(result).isInstanceOf(JsonValue.Obj.class);
    }


    @Test
    void remoteDiscoveryFailureIsReported() {
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), "{\"jsonrpc\":\"2.0\",\"id\":0,\"error\":{\"code\":-32601,\"message\":\"not found\"}}".getBytes()));
        McpDiscoveryClient client = new McpDiscoveryClient(transport,
                new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                        false, false, true, 5_000, 5_000, 5_000));

        assertThatThrownBy(client::listTools).isInstanceOf(McpFailure.class);
    }

    @Test
    void managerRegistersDiscoveredTools() {
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of(), INITIALIZE_RESPONSE.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), TOOLS_RESPONSE.getBytes()));
        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, transport, null);
        manager.add(new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                false, false, true, 5_000, 5_000, 5_000));

        assertThat(manager.status("github")).isEqualTo(McpServerStatus.CONNECTED);
        assertThat(registry.list()).extracting(ToolDefinition::effectiveName)
                .containsExactlyInAnyOrder("github_search", "github_issue");
        assertThat(manager.discovered("github")).hasSize(2);
    }

    @Test
    void disabledServerRegistersNothing() {
        ToolRegistry registry = new ToolRegistry();
        McpServerManager manager = new McpServerManager(registry, null, null);
        manager.add(new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                false, true, true, 5_000, 5_000, 5_000));

        assertThat(manager.status("github")).isEqualTo(McpServerStatus.DISABLED);
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void instructionsExcludeEntirelyDeniedServers() {
        Map<String, List<McpRegistrar.McpToolSpec>> discovered = Map.of(
                "github", List.of(new McpRegistrar.McpToolSpec("search", "search", Schema.obj(Map.of()))),
                "blocked", List.of(new McpRegistrar.McpToolSpec("tool", "tool", Schema.obj(Map.of()))));

        String block = McpInstructions.build(discovered, List.of(
                new Rule("github_search", "*", Effect.DENY),
                new Rule("blocked_tool", "*", Effect.DENY)));

        // github has one tool denied -> entirely denied -> excluded
        assertThat(block).doesNotContain("github");
        // blocked server's single tool is denied -> excluded
        assertThat(block).doesNotContain("blocked");
    }

    @Test
    void instructionsIncludeServersWithAtLeastOneAllowedTool() {
        Map<String, List<McpRegistrar.McpToolSpec>> discovered = Map.of(
                "github", List.of(
                        new McpRegistrar.McpToolSpec("search", "search", Schema.obj(Map.of())),
                        new McpRegistrar.McpToolSpec("issue", "issue", Schema.obj(Map.of()))));

        String block = McpInstructions.build(discovered, List.of(
                new Rule("github_search", "*", Effect.DENY),
                new Rule("blocked_tool", "*", Effect.DENY)));

        assertThat(block).contains("<mcp_instructions>");
        assertThat(block).contains("github");
        assertThat(block).contains("issue");
        assertThat(block).doesNotContain("search");
    }

    @Test
    void stdioDiscoveryUsesTheProcessOutput() {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":\"2025-11-25\"}}";
        String tools = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                + "{\"name\":\"list\",\"description\":\"list files\",\"inputSchema\":{\"type\":\"object\"}}]}}";
        FakeProcess process = new FakeProcess(new ProcessRunner.Exit(0, initialize + "\n" + tools + "\n", "", false));
        McpStdioClient client = new McpStdioClient(process,
                new McpServerConfig.Local("fs", List.of("mcp-server"), ".", Map.of(),
                        false, true, 5_000, 5_000, 5_000));

        assertThat(client.listTools()).extracting(McpRegistrar.McpToolSpec::name)
                .containsExactly("list");
    }

    @Test
    void remoteDiscoveryHandlesSseResponses() {
        String initSse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}}";
        String toolsSse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                + "{\"name\":\"search\",\"description\":\"search code\",\"inputSchema\":{\"type\":\"object\"}}]}}";
        ScriptedHttp transport = new ScriptedHttp(
                new ScriptedHttp.Response(200, Map.of("Mcp-Session-Id", List.of("sid")), initSse.getBytes()),
                new ScriptedHttp.Response(200, Map.of(), toolsSse.getBytes()));
        McpDiscoveryClient client = new McpDiscoveryClient(transport,
                new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                        false, false, true, 5_000, 5_000, 5_000));

        assertThat(client.listTools()).extracting(McpRegistrar.McpToolSpec::name)
                .containsExactly("search");
    }


    private static final class Schema {
        static de.augmentia.oc.engine.core.json.Schema obj(Map<String, de.augmentia.oc.engine.core.json.Schema> fields) {
            return de.augmentia.oc.engine.core.json.Schema.obj(fields);
        }
    }
}
