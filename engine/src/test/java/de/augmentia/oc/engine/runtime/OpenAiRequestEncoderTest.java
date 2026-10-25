package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.*;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.runtime.provider.client.OpenAiRequestEncoder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestEncoderTest {

    @Test
    void encodesModelMessagesToolsAndBudget() {
        ModelRequest request = new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of("system one", "system two"),
                List.of(new ModelMessage.User(List.of(new ModelContent.Text("hello"))),
                        new ModelMessage.Assistant(List.of(new ModelContent.Text("hi"))),
                        new ModelMessage.ToolResult(CallId.create(), new ToolName("read"),
                                new OutcomePayload.Text("content"))),
                List.of(tool()), Option.some(new Choice.Required()),
                new GenerationOptions(Option.some(0.2), Option.some(300), Option.some(0.9), List.of("STOP")),
                Map.of(), Map.of(), Option.none());

        String json = new OpenAiRequestEncoder("gpt-4o-mini").apply(request);
        JsonValue root = Json.parse(json).valueOrNull();
        JsonValue.Obj object = (JsonValue.Obj) root;

        assertThat(((JsonValue.Str) object.values().get("model")).value()).isEqualTo("gpt-4o-mini");
        assertThat(((JsonValue.Bool) object.values().get("stream")).value()).isTrue();
        assertThat(((JsonValue.Num) object.values().get("max_tokens")).value().intValue()).isEqualTo(300);
        assertThat(((JsonValue.Str) object.values().get("tool_choice")).value()).isEqualTo("required");

        JsonValue.Arr messages = (JsonValue.Arr) object.values().get("messages");
        assertThat(messages.values()).hasSize(4);
        assertThat(((JsonValue.Str) ((JsonValue.Obj) messages.values().get(0)).values().get("role")).value())
                .isEqualTo("system");
        assertThat(((JsonValue.Str) ((JsonValue.Obj) messages.values().get(2)).values().get("role")).value())
                .isEqualTo("assistant");
        assertThat(((JsonValue.Str) ((JsonValue.Obj) messages.values().get(3)).values().get("role")).value())
                .isEqualTo("tool");

        JsonValue.Arr tools = (JsonValue.Arr) object.values().get("tools");
        assertThat(tools.values()).hasSize(1);
    }

    @Test
    void passesProviderOptionsThroughUnchanged() {
        ModelRequest request = new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(), List.of(), List.of(), Option.none(),
                GenerationOptions.defaults(),
                Map.of("reasoning_effort", "none", "seed", 7L),
                Map.of(), Option.none());

        JsonValue.Obj object = (JsonValue.Obj) Json.parse(
                new OpenAiRequestEncoder("deepseek-v4.1-flash").apply(request)).valueOrNull();

        assertThat(((JsonValue.Str) object.values().get("reasoning_effort")).value()).isEqualTo("none");
        assertThat(((JsonValue.Num) object.values().get("seed")).value().longValue()).isEqualTo(7L);
    }

    @Test
    void toolChoiceCanBeNone() {
        ModelRequest request = new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(), List.of(), List.of(tool()), Option.some(new Choice.None()),
                GenerationOptions.defaults(), Map.of(), Map.of(), Option.none());
        String json = new OpenAiRequestEncoder("m").apply(request);
        JsonValue.Obj object = (JsonValue.Obj) Json.parse(json).valueOrNull();
        assertThat(((JsonValue.Str) object.values().get("tool_choice")).value()).isEqualTo("none");
    }

    @Test
    void strictToolEncodesRequiredNullableFields() {
        ToolDefinition strictTool = new ToolDefinition(new ToolName("submit_result"), "submit",
                Schema.obj(Map.of("answer", Schema.str(), "note", Schema.opt(Schema.str()))),
                Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")),
                ToolOptions.defaults().withStrict(true));
        ModelRequest request = new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(), List.of(), List.of(strictTool), Option.none(),
                GenerationOptions.defaults(), Map.of(), Map.of(), Option.none());

        JsonValue.Obj root = (JsonValue.Obj) Json.parse(
                new OpenAiRequestEncoder("gpt-4o-mini").apply(request)).valueOrNull();
        JsonValue.Arr tools = (JsonValue.Arr) root.values().get("tools");
        JsonValue.Obj function = (JsonValue.Obj) ((JsonValue.Obj) tools.values().get(0))
                .values().get("function");

        assertThat(((JsonValue.Bool) function.values().get("strict")).value()).isTrue();
        JsonValue.Obj parameters = (JsonValue.Obj) function.values().get("parameters");
        assertThat(((JsonValue.Bool) parameters.values().get("additionalProperties")).value()).isFalse();

        JsonValue.Arr required = (JsonValue.Arr) parameters.values().get("required");
        List<String> requiredNames = required.values().stream()
                .map(value -> ((JsonValue.Str) value).value()).toList();
        assertThat(requiredNames).containsExactlyInAnyOrder("answer", "note");

        JsonValue.Obj properties = (JsonValue.Obj) parameters.values().get("properties");
        JsonValue.Obj note = (JsonValue.Obj) properties.values().get("note");
        JsonValue.Arr anyOf = (JsonValue.Arr) note.values().get("anyOf");
        assertThat(anyOf.values()).hasSize(2);
        assertThat(((JsonValue.Str) ((JsonValue.Obj) anyOf.values().get(1)).values().get("type")).value())
                .isEqualTo("null");
    }

    private static ToolDefinition tool() {
        return new ToolDefinition(new ToolName("read"), "read a file",
                Schema.obj(Map.of("path", Schema.str(),
                        "limit", Schema.opt(Schema.intg()))),
                Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")),
                null);
    }
}
