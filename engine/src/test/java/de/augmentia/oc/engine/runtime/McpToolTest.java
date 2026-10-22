package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.runtime.tool.mcp.McpClient;
import de.augmentia.oc.engine.runtime.tool.mcp.McpFailure;
import de.augmentia.oc.engine.runtime.tool.mcp.McpRegistrar;
import de.augmentia.oc.engine.runtime.tool.mcp.McpTool;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolTest {

    @Test
    void toolNameIsNamespacedAndActionMatchesTheName() {
        ToolDefinition definition = McpTool.definition("github", "search", "search code",
                Schema.obj(Map.of()), (tool, arguments) -> JsonValue.ofNull());

        assertThat(definition.effectiveName()).isEqualTo("github_search");
        assertThat(definition.options().permission().orElseThrow()).isEqualTo("github_search");
    }

    @Test
    void textContentIsMappedToToolContent() {
        ToolDefinition definition = McpTool.definition("github", "search", "",
                Schema.obj(Map.of()), (tool, arguments) -> result(false, "found"));

        ToolOutcome outcome = execute(definition);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().content())
                .containsExactly(new ToolContent.TextItem("found"));
    }

    @Test
    void isErrorBecomesAToolFailure() {
        ToolDefinition definition = McpTool.definition("github", "search", "",
                Schema.obj(Map.of()), (tool, arguments) -> result(true, "bad request"));

        ToolOutcome outcome = execute(definition);

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).isEqualTo("bad request");
    }

    @Test
    void mcpFailureIsAToolFailure() {
        ToolDefinition definition = McpTool.definition("github", "search", "",
                Schema.obj(Map.of()), (tool, arguments) -> {
                    throw new McpFailure("connection lost");
                });

        assertThat(execute(definition)).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void registrarRegistersAllToolsOfAServer() {
        ToolRegistry registry = new ToolRegistry();
        McpRegistrar registrar = new McpRegistrar();
        McpClient client = (tool, arguments) -> JsonValue.ofNull();

        var registrations = registrar.register(registry, "github", List.of(
                new McpRegistrar.McpToolSpec("search", "search", Schema.obj(Map.of())),
                new McpRegistrar.McpToolSpec("issue", "issue", Schema.obj(Map.of()))), client);

        assertThat(registrations).hasSize(2);
        assertThat(registry.list()).extracting(ToolDefinition::effectiveName)
                .containsExactly("github_search", "github_issue");
    }

    private static ToolOutcome execute(ToolDefinition definition) {
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(),
                MessageId.create(), CallId.create());
        return definition.execute().execute(JsonValue.of(Map.of()), context);
    }

    private static JsonValue result(boolean error, String text) {
        return JsonValue.of(Map.of(
                "isError", JsonValue.of(error),
                "content", JsonValue.of(List.of(JsonValue.of(Map.of(
                        "type", JsonValue.of("text"),
                        "text", JsonValue.of(text)))))));
    }
}
