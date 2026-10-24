package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.ToolContent;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Encodes a neutral {@link ModelRequest} as an OpenAI Chat Completions request. */
public final class OpenAiRequestEncoder implements Function<ModelRequest, String> {

    private final String modelName;
    private final OpenAiSchema schemas = new OpenAiSchema();

    public OpenAiRequestEncoder(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName must not be blank");
        }
        this.modelName = modelName;
    }

    @Override
    public String apply(ModelRequest request) {
        Map<String, JsonValue> body = new LinkedHashMap<>();
        body.put("model", JsonValue.of(modelName));
        body.put("stream", JsonValue.of(true));
        body.put("stream_options", JsonValue.of(Map.of("include_usage", JsonValue.of(true))));
        // V-14: provider options are passed through unchanged.
        request.providerOptions().forEach((key, value) -> body.put(key, toJson(value)));

        List<JsonValue> messages = new ArrayList<>();
        if (!request.system().isEmpty()) {
            messages.add(message("system", JsonValue.of(String.join("\n\n", request.system()))));
        }
        for (ModelMessage message : request.messages()) {
            messages.add(encodeMessage(message));
        }
        body.put("messages", JsonValue.of(messages));

        if (!request.tools().isEmpty()) {
            body.put("tools", JsonValue.of(request.tools().stream().map(this::tool).toList()));
            body.put("tool_choice", toolChoice(request.toolChoice()));
        }

        request.generation().maxOutputTokens().ifPresent(value ->
                body.put("max_tokens", JsonValue.of(value.longValue())));
        request.generation().temperature().ifPresent(value ->
                body.put("temperature", JsonValue.of(value.doubleValue())));
        request.generation().topP().ifPresent(value ->
                body.put("top_p", JsonValue.of(value.doubleValue())));
        if (!request.generation().stop().isEmpty()) {
            body.put("stop", JsonValue.of(request.generation().stop().stream().map(JsonValue::of).toList()));
        }
        return Json.encode(JsonValue.of(body));
    }

    private JsonValue encodeMessage(ModelMessage message) {
        return switch (message) {
            case ModelMessage.User user -> message("user", content(user.content()));
            case ModelMessage.Assistant assistant -> assistant(assistant.content());
            case ModelMessage.ToolResult result -> toolResult(result);
        };
    }

    private JsonValue assistant(List<ModelContent> content) {
        Map<String, JsonValue> message = new LinkedHashMap<>();
        message.put("role", JsonValue.of("assistant"));
        List<JsonValue> texts = new ArrayList<>();
        List<JsonValue> calls = new ArrayList<>();
        for (ModelContent item : content) {
            switch (item) {
                case ModelContent.Text text -> texts.add(JsonValue.of(text.text()));
                case ModelContent.Media ignored -> { }
                case ModelContent.ToolCall call -> calls.add(JsonValue.of(Map.of(
                        "id", JsonValue.of(call.callId().value()),
                        "type", JsonValue.of("function"),
                        "function", JsonValue.of(Map.of(
                                "name", JsonValue.of(call.name().value()),
                                "arguments", JsonValue.of(Json.encode(call.input())))))));
            }
        }
        if (!texts.isEmpty()) {
            message.put("content", JsonValue.of(texts.stream()
                    .map(value -> ((JsonValue.Str) value).value())
                    .reduce("", String::concat)));
        }
        if (!calls.isEmpty()) message.put("tool_calls", JsonValue.of(calls));
        return JsonValue.of(message);
    }

    private JsonValue toolResult(ModelMessage.ToolResult result) {
        Map<String, JsonValue> message = new LinkedHashMap<>();
        message.put("role", JsonValue.of("tool"));
        message.put("tool_call_id", JsonValue.of(result.callId().value()));
        message.put("content", JsonValue.of(outcomeText(result.outcome())));
        return JsonValue.of(message);
    }

    private JsonValue content(List<ModelContent> content) {
        boolean onlyText = content.stream().allMatch(ModelContent.Text.class::isInstance);
        if (onlyText) {
            return JsonValue.of(content.stream()
                    .map(ModelContent.Text.class::cast)
                    .map(ModelContent.Text::text)
                    .reduce("", String::concat));
        }
        List<JsonValue> parts = new ArrayList<>();
        for (ModelContent item : content) {
            switch (item) {
                case ModelContent.Text text -> parts.add(JsonValue.of(Map.of(
                        "type", JsonValue.of("text"),
                        "text", JsonValue.of(text.text()))));
                case ModelContent.Media media -> parts.add(JsonValue.of(Map.of(
                        "type", JsonValue.of("image_url"),
                        "image_url", JsonValue.of(Map.of(
                                "url", JsonValue.of("data:" + media.modality().name().toLowerCase()
                                        + "/octet-stream;base64,"
                                        + Base64.getEncoder().encodeToString(media.data())))))));
                case ModelContent.ToolCall call -> parts.add(JsonValue.of(Map.of(
                        "type", JsonValue.of("text"),
                        "text", JsonValue.of(Json.encode(call.input())))));
            }
        }
        return JsonValue.of(parts);
    }

    private JsonValue tool(ToolDefinition definition) {
        return JsonValue.of(Map.of(
                "type", JsonValue.of("function"),
                "function", JsonValue.of(Map.of(
                        "name", JsonValue.of(definition.effectiveName()),
                        "description", JsonValue.of(definition.description()),
                        "parameters", schemas.encode(definition.inputSchema())))));
    }

    private JsonValue toolChoice(de.augmentia.oc.engine.core.model.Option<Choice> choice) {
        if (choice == null || choice.isEmpty()) return JsonValue.of("auto");
        return switch (choice.orElseThrow()) {
            case Choice.Free ignored -> JsonValue.of("auto");
            case Choice.None ignored -> JsonValue.of("none");
            case Choice.Required ignored -> JsonValue.of("required");
            case Choice.One one -> JsonValue.of(Map.of(
                    "type", JsonValue.of("function"),
                    "function", JsonValue.of(Map.of("name", JsonValue.of(one.name().value())))));
        };
    }

    private static JsonValue message(String role, JsonValue content) {
        return JsonValue.of(Map.of("role", JsonValue.of(role), "content", content));
    }

    /** Converts a neutral provider option value into JSON. */
    private static JsonValue toJson(Object value) {
        return switch (value) {
            case null -> JsonValue.ofNull();
            case JsonValue json -> json;
            case Boolean bool -> JsonValue.of(bool);
            case Integer number -> JsonValue.of(number.longValue());
            case Long number -> JsonValue.of(number);
            case Float number -> JsonValue.of(number.doubleValue());
            case Double number -> JsonValue.of(number);
            case java.math.BigDecimal number -> JsonValue.of(number);
            case String text -> JsonValue.of(text);
            case Map<?, ?> map -> JsonValue.of(map.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            entry -> toJson(entry.getValue()))));
            case List<?> list -> JsonValue.of(list.stream().map(OpenAiRequestEncoder::toJson).toList());
            default -> JsonValue.of(String.valueOf(value));
        };
    }

    private static String outcomeText(OutcomePayload outcome) {
        return switch (outcome) {
            case OutcomePayload.Text text -> text.text();
            case OutcomePayload.Json json -> Json.encode(json.value());
            case OutcomePayload.Content content -> content.items().stream()
                    .filter(ToolContent.TextItem.class::isInstance)
                    .map(ToolContent.TextItem.class::cast)
                    .map(ToolContent.TextItem::text)
                    .reduce("", String::concat);
        };
    }
}
