package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.runtime.tool.mcp.McpResource;
import de.augmentia.oc.engine.runtime.tool.mcp.McpResourceTools;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.testing.ScriptedHttp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpResourceToolsTest {

    private static final String INIT = """
            {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}""";
    private static final String TOOLS = """
            {"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"search","inputSchema":{"type":"object"}}]}}""";
    private static final String RESOURCES = """
            {"jsonrpc":"2.0","id":3,"result":{"resources":[
              {"uri":"file:///a","name":"a","mimeType":"text/plain"}]}}""";
    private static final String TEMPLATES = """
            {"jsonrpc":"2.0","id":3,"result":{"resourceTemplates":[
              {"uriTemplate":"file:///{x}","name":"t"}]}}""";
    private static final String CONTENT = """
            {"jsonrpc":"2.0","id":3,"result":{"contents":[
              {"uri":"file:///a","mimeType":"text/plain","text":"hello"}]}}""";

    @Test
    void listsResourcesAndTemplates() {
        ScriptedHttp transport = script(INIT, TOOLS, RESOURCES, TEMPLATES);
        McpServerManager manager = manager(transport);

        ToolOutcome outcome = execute(McpResourceTools.list(manager), Map.of());

        assertThat(text(outcome)).contains("file:///a").contains("file:///{x}");
    }

    @Test
    void readsResourceContent() {
        ScriptedHttp transport = script(INIT, TOOLS, CONTENT);
        McpServerManager manager = manager(transport);

        ToolOutcome outcome = execute(McpResourceTools.read(manager), Map.of(
                "server", JsonValue.of("github"),
                "uri", JsonValue.of("file:///a")));

        assertThat(text(outcome)).contains("hello");
    }

    @Test
    void unknownServerIsAToolFailure() {
        McpServerManager manager = new McpServerManager(new ToolRegistry(), null, null);

        ToolOutcome outcome = execute(McpResourceTools.read(manager), Map.of(
                "server", JsonValue.of("missing"),
                "uri", JsonValue.of("file:///a")));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void resourceRecordsNormalizeNulls() {
        McpResource.Resource resource = new McpResource.Resource(null, null, null, null, null);
        McpResource.Template template = new McpResource.Template(null, null, null, null, null);
        McpResource.Content content = new McpResource.Content(null, null, null, null);

        assertThat(resource.server()).isEmpty();
        assertThat(template.uriTemplate()).isEmpty();
        assertThat(content.text()).isEmpty();
    }

    private static McpServerManager manager(ScriptedHttp transport) {
        McpServerManager manager = new McpServerManager(new ToolRegistry(), transport, null);
        manager.add(new McpServerConfig.Remote("github", "https://example.test/mcp", Map.of(),
                false, false, true, 5_000, 5_000, 5_000));
        return manager;
    }

    private static ScriptedHttp script(String... bodies) {
        ScriptedHttp.Response[] responses = new ScriptedHttp.Response[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            responses[i] = new ScriptedHttp.Response(200, Map.of(), bodies[i].getBytes());
        }
        return new ScriptedHttp(responses);
    }

    private static ToolOutcome execute(de.augmentia.oc.engine.core.contract.tool.ToolDefinition definition,
                                       Map<String, JsonValue> input) {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create());
        return definition.execute().execute(JsonValue.of(input), context);
    }

    private static String text(ToolOutcome outcome) {
        return ((ToolContent.TextItem) ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }
}
