package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.Modality;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Branch coverage for the OpenAI request encoder. */
class OpenAiRequestEncoderCoverageTest {

    private static JsonValue.Obj encode(List<String> system, List<ModelMessage> messages,
                                        List<ToolDefinition> tools, Option<Choice> choice,
                                        GenerationOptions generation, Map<String, Object> providerOptions) {
        ModelRequest request = new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                system, messages, tools, choice, generation, providerOptions, Map.of(), Option.none());
        return (JsonValue.Obj) Json.parse(new OpenAiRequestEncoder("gpt-test").apply(request)).valueOrNull();
    }

    private static JsonValue.Obj encode(List<ModelMessage> messages) {
        return encode(List.of(), messages, List.of(), Option.none(), GenerationOptions.defaults(), Map.of());
    }

    private static JsonValue.Obj firstMessage(JsonValue.Obj root) {
        return obj(arr(root.values().get("messages")).values().getFirst());
    }

    private static JsonValue.Obj obj(JsonValue value) {
        return (JsonValue.Obj) value;
    }

    private static JsonValue.Arr arr(JsonValue value) {
        return (JsonValue.Arr) value;
    }

    private static String str(JsonValue.Obj object, String key) {
        return ((JsonValue.Str) object.values().get(key)).value();
    }

    private static JsonValue input(String json) {
        return Json.parse(json).valueOrNull();
    }

    private static ToolDefinition tool(boolean strict) {
        return new ToolDefinition(new ToolName("read"), "read a file",
                Schema.obj(Map.of("path", Schema.str())), Option.none(),
                (in, ctx) -> new ToolOutcome.Ok(ToolResult.of("ok")),
                ToolOptions.defaults().withStrict(strict));
    }

    @Test
    void omitsSystemMessageAndOptionalFieldsWhenEmpty() {
        JsonValue.Obj root = encode(List.of(new ModelMessage.User(List.of(new ModelContent.Text("hi")))));

        assertThat(arr(root.values().get("messages")).values()).hasSize(1);
        assertThat(str(firstMessage(root), "role")).isEqualTo("user");
        assertThat(str(firstMessage(root), "content")).isEqualTo("hi");
        assertThat(root.values()).doesNotContainKeys("tools", "tool_choice", "max_tokens",
                "temperature", "top_p", "stop");
    }

    @Test
    void joinsMultipleSystemMessagesWithBlankLine() {
        JsonValue.Obj root = encode(List.of("one", "two"),
                List.of(), List.of(), Option.none(), GenerationOptions.defaults(), Map.of());
        assertThat(str(firstMessage(root), "role")).isEqualTo("system");
        assertThat(str(firstMessage(root), "content")).isEqualTo("one\n\ntwo");
    }

    @Test
    void userMixedContentEncodesTextMediaAndToolCallParts() {
        ModelContent.ToolCall call = new ModelContent.ToolCall(CallId.create(),
                new ToolName("read"), input("{\"x\":1}"));
        JsonValue.Obj root = encode(List.of(new ModelMessage.User(List.of(
                new ModelContent.Text("hello"),
                new ModelContent.Media(Modality.IMAGE, new byte[]{1, 2, 3}, Option.some("a.png")),
                call))));

        JsonValue.Arr parts = arr(firstMessage(root).values().get("content"));
        assertThat(parts.values()).hasSize(3);
        assertThat(str(obj(parts.values().get(0)), "type")).isEqualTo("text");
        assertThat(str(obj(parts.values().get(0)), "text")).isEqualTo("hello");

        JsonValue.Obj image = obj(parts.values().get(1));
        assertThat(str(image, "type")).isEqualTo("image_url");
        assertThat(str(obj(image.values().get("image_url")), "url"))
                .startsWith("data:image/octet-stream;base64,");

        assertThat(str(obj(parts.values().get(2)), "type")).isEqualTo("text");
        assertThat(str(obj(parts.values().get(2)), "text")).isEqualTo("{\"x\":1}");
    }

    @Test
    void assistantEncodesTextAndToolCalls() {
        ModelContent.ToolCall call = new ModelContent.ToolCall(CallId.create(),
                new ToolName("read"), input("{\"x\":1}"));
        JsonValue.Obj message = firstMessage(encode(List.of(new ModelMessage.Assistant(List.of(
                new ModelContent.Text("a"), new ModelContent.Text("b"), call)))));

        assertThat(str(message, "role")).isEqualTo("assistant");
        assertThat(str(message, "content")).isEqualTo("ab");

        JsonValue.Arr calls = arr(message.values().get("tool_calls"));
        assertThat(calls.values()).hasSize(1);
        JsonValue.Obj function = obj(obj(calls.values().getFirst()).values().get("function"));
        assertThat(str(function, "name")).isEqualTo("read");
        assertThat(str(function, "arguments")).isEqualTo("{\"x\":1}");
    }

    @Test
    void assistantWithOnlyMediaHasNoContentOrToolCalls() {
        JsonValue.Obj message = firstMessage(encode(List.of(new ModelMessage.Assistant(List.of(
                new ModelContent.Media(Modality.AUDIO, new byte[0], Option.none()))))));

        assertThat(str(message, "role")).isEqualTo("assistant");
        assertThat(message.values()).doesNotContainKeys("content", "tool_calls");
    }

    @Test
    void toolResultEncodesTextJsonAndContentOutcomes() {
        assertThat(toolResultContent(new OutcomePayload.Text("plain"))).isEqualTo("plain");
        assertThat(toolResultContent(new OutcomePayload.Json(input("{\"a\":1}")))).isEqualTo("{\"a\":1}");
        assertThat(toolResultContent(new OutcomePayload.Content(
                List.of(new ToolContent.TextItem("a"), new ToolContent.TextItem("b")),
                Option.none()))).isEqualTo("ab");
    }

    @Test
    void toolResultFiltersNonTextContentItems() {
        String content = toolResultContent(new OutcomePayload.Content(List.of(
                new ToolContent.TextItem("a"),
                new ToolContent.FileItem("artifact://s/1", "text/plain", Option.none())),
                Option.none()));
        assertThat(content).isEqualTo("a");
    }

    @Test
    void nonStrictAndStrictToolsEncodeDifferentFunctions() {
        JsonValue.Obj nonStrict = encode(List.of(), List.of(), List.of(tool(false)), Option.none(),
                GenerationOptions.defaults(), Map.of());
        JsonValue.Obj nonStrictFunction = obj(obj(arr(nonStrict.values().get("tools")).values().getFirst())
                .values().get("function"));
        assertThat(str(nonStrictFunction, "name")).isEqualTo("read");
        assertThat(nonStrictFunction.values()).doesNotContainKey("strict");

        JsonValue.Obj strict = encode(List.of(), List.of(), List.of(tool(true)), Option.none(),
                GenerationOptions.defaults(), Map.of());
        JsonValue.Obj strictFunction = obj(obj(arr(strict.values().get("tools")).values().getFirst())
                .values().get("function"));
        assertThat(((JsonValue.Bool) strictFunction.values().get("strict")).value()).isTrue();
    }

    @Test
    void encodesAllToolChoiceVariants() {
        assertThat(simpleToolChoice(Option.none())).isEqualTo("auto");
        assertThat(simpleToolChoice(Option.some(new Choice.Free()))).isEqualTo("auto");
        assertThat(simpleToolChoice(Option.some(new Choice.None()))).isEqualTo("none");
        assertThat(simpleToolChoice(Option.some(new Choice.Required()))).isEqualTo("required");

        JsonValue.Obj one = obj(toolChoice(Option.some(new Choice.One(new ToolName("read")))));
        assertThat(str(one, "type")).isEqualTo("function");
        assertThat(str(obj(one.values().get("function")), "name")).isEqualTo("read");
    }

    @Test
    void omitsGenerationOptionsWhenAbsent() {
        JsonValue.Obj root = encode(List.of());
        assertThat(root.values()).doesNotContainKeys("max_tokens", "temperature", "top_p", "stop");
    }

    @Test
    void encodesGenerationOptionsAndStopList() {
        JsonValue.Obj root = encode(List.of(), List.of(), List.of(), Option.none(),
                new GenerationOptions(Option.some(0.2), Option.some(300), Option.some(0.9),
                        List.of("STOP", "END")), Map.of());

        assertThat(((JsonValue.Num) root.values().get("temperature")).value()).isEqualByComparingTo("0.2");
        assertThat(((JsonValue.Num) root.values().get("max_tokens")).value().intValue()).isEqualTo(300);
        assertThat(((JsonValue.Num) root.values().get("top_p")).value()).isEqualByComparingTo("0.9");
        assertThat(arr(root.values().get("stop")).values()).hasSize(2);
    }

    @Test
    void encodesEveryProviderOptionValueType() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("missing", null);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("bool", true);
        options.put("integer", 7);
        options.put("long", 8L);
        options.put("float", 1.5f);
        options.put("double", 2.5d);
        options.put("big", new BigDecimal("3.14"));
        options.put("text", "value");
        options.put("raw", input("{\"x\":1}"));
        options.put("nested", nested);
        options.put("list", List.of(1, "two", Map.of("k", List.of(true))));
        options.put("other", new Object());

        JsonValue.Obj root = encode(List.of(), List.of(), List.of(), Option.none(),
                GenerationOptions.defaults(), options);

        assertThat(((JsonValue.Bool) root.values().get("bool")).value()).isTrue();
        assertThat(((JsonValue.Num) root.values().get("integer")).value().intValue()).isEqualTo(7);
        assertThat(((JsonValue.Num) root.values().get("long")).value().longValue()).isEqualTo(8L);
        assertThat(((JsonValue.Num) root.values().get("float")).value()).isEqualByComparingTo("1.5");
        assertThat(((JsonValue.Num) root.values().get("double")).value()).isEqualByComparingTo("2.5");
        assertThat(((JsonValue.Num) root.values().get("big")).value()).isEqualByComparingTo("3.14");
        assertThat(str(root, "text")).isEqualTo("value");
        assertThat(obj(root.values().get("raw")).values()).containsKey("x");
        assertThat(obj(root.values().get("nested")).values().get("missing"))
                .isInstanceOf(JsonValue.Null.class);
        assertThat(arr(root.values().get("list")).values()).hasSize(3);
        assertThat(str(root, "other")).startsWith("java.lang.Object");
    }

    private static JsonValue toolChoice(Option<Choice> choice) {
        return encode(List.of(), List.of(), List.of(tool(false)), choice,
                GenerationOptions.defaults(), Map.of()).values().get("tool_choice");
    }

    private static String simpleToolChoice(Option<Choice> choice) {
        return ((JsonValue.Str) toolChoice(choice)).value();
    }

    private static String toolResultContent(OutcomePayload outcome) {
        ModelMessage.ToolResult result = new ModelMessage.ToolResult(CallId.create(),
                new ToolName("read"), outcome);
        JsonValue.Obj message = firstMessage(encode(List.of(result)));
        assertThat(str(message, "role")).isEqualTo("tool");
        assertThat(message.values()).containsKey("tool_call_id");
        return str(message, "content");
    }
}
