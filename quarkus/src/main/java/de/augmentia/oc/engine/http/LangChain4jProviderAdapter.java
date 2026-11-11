package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.contract.provider.*;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LangChain4j implementation of {@link ProviderAdapter}. Mapping between the neutral
 * engine contract and LangChain4j types stays inside this adapter.
 */
public final class LangChain4jProviderAdapter implements ProviderAdapter {

    private static final ThreadLocal<java.util.Map<String, String>> REQUEST_HEADERS =
            ThreadLocal.withInitial(java.util.Map::of);

    private final StreamingChatModel streaming;
    private final ChatModel chat;
    private final StreamingChatModel internalStreaming;
    private final java.util.concurrent.ConcurrentMap<CallId, String> originalToolCallIds =
            new ConcurrentHashMap<>();

    public LangChain4jProviderAdapter() {
        this(null, null);
    }

    public LangChain4jProviderAdapter(StreamingChatModel streaming) {
        this(streaming, null);
    }

    public LangChain4jProviderAdapter(ChatModel chat) {
        this(null, chat);
    }

    public LangChain4jProviderAdapter(StreamingChatModel streaming, ChatModel chat) {
        this.streaming = streaming;
        this.chat = chat;
        if (streaming == null && chat == null) {
            String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL",
                    System.getProperty("OPENAI_BASE_URL", "https://api.openai.com/v1"));
            String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY",
                    System.getProperty("OPENAI_API_KEY", "demo-key"));
            this.internalStreaming = OpenAiStreamingChatModel.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .customHeaders(() -> {
                        java.util.Map<String, String> headers = REQUEST_HEADERS.get();
                        return headers == null ? java.util.Map.of() : headers;
                    })
                    .logRequests(false)
                    .logResponses(false)
                    .timeout(java.time.Duration.ofMinutes(2))
                    .build();
        } else {
            this.internalStreaming = null;
        }
    }

    @Override
    public java.util.stream.Stream<ModelEvent> stream(ModelRequest request) {
        List<ModelEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        REQUEST_HEADERS.set(request.headers() == null ? java.util.Map.of() : request.headers());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ChatRequest lcRequest;
        try {
            lcRequest = toChatRequest(request);
        } catch (Throwable t) {
            events.add(new ModelEvent.ProviderFailed(classify(t), t.getMessage() == null ? t.toString() : t.getMessage(),
                    Option.none(), Option.none()));
            REQUEST_HEADERS.remove();
            return events.stream();
        }

        StreamingChatResponseHandler handler = new StreamingChatResponseHandler() {
            private final StringBuilder text = new StringBuilder();
            private final StringBuilder reasoning = new StringBuilder();
            private boolean textStarted;
            private boolean reasoningStarted;
            private final List<String> completedToolCallIds = new ArrayList<>();

            @Override
            public void onPartialResponse(String token) {
                if (token == null || token.isEmpty()) return;
                if (!textStarted) {
                    events.add(new ModelEvent.TextStarted("block-1"));
                    textStarted = true;
                }
                events.add(new ModelEvent.TextDelta("block-1", token));
                text.append(token);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                if (partialThinking == null || partialThinking.text() == null || partialThinking.text().isEmpty()) return;
                if (!reasoningStarted) {
                    events.add(new ModelEvent.ReasoningStarted("reasoning-1"));
                    reasoningStarted = true;
                }
                events.add(new ModelEvent.ReasoningDelta("reasoning-1", partialThinking.text()));
                reasoning.append(partialThinking.text());
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                if (completeToolCall == null || completeToolCall.toolExecutionRequest() == null) return;
                ToolExecutionRequest req = completeToolCall.toolExecutionRequest();
                String lcId = req.id();
                CallId callId = mapToolCallId(lcId);
                ToolName name = new ToolName(req.name());
                String args = req.arguments() == null ? "" : req.arguments();
                events.add(new ModelEvent.ToolInputStarted(callId, name));
                events.add(new ModelEvent.ToolInputEnded(callId, args));
                JsonValue input = parseJson(args);
                events.add(new ModelEvent.ToolCallRequested(callId, name, input));
                completedToolCallIds.add(lcId == null ? "" : lcId);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                try {
                    String finalText = response == null || response.aiMessage() == null ? "" : response.aiMessage().text();
                    if (finalText == null) finalText = "";
                    if (!textStarted && !finalText.isEmpty()) {
                        events.add(new ModelEvent.TextStarted("block-1"));
                        textStarted = true;
                        events.add(new ModelEvent.TextDelta("block-1", finalText));
                        text.append(finalText);
                    }
                    if (textStarted) {
                        events.add(new ModelEvent.TextEnded("block-1", text.toString()));
                    }
                    if (reasoningStarted) {
                        events.add(new ModelEvent.ReasoningEnded("reasoning-1", reasoning.toString()));
                    }

                    if (response != null && response.aiMessage() != null
                            && response.aiMessage().toolExecutionRequests() != null) {
                        for (ToolExecutionRequest req : response.aiMessage().toolExecutionRequests()) {
                            String lcId = req.id();
                            if (lcId != null && completedToolCallIds.contains(lcId)) continue;
                            CallId callId = mapToolCallId(lcId);
                            ToolName name = new ToolName(req.name());
                            String args = req.arguments() == null ? "" : req.arguments();
                            events.add(new ModelEvent.ToolInputStarted(callId, name));
                            events.add(new ModelEvent.ToolInputEnded(callId, args));
                            events.add(new ModelEvent.ToolCallRequested(callId, name, parseJson(args)));
                        }
                    }

                    FinishReason reason = mapFinishReason(response == null ? null : response.finishReason());
                    TokenCount usage = mapUsage(response == null ? null : response.tokenUsage());
                    JsonValue raw = JsonValue.ofNull();
                    if (response != null && response.metadata() != null) {
                        // Pass through a small metadata object when available.
                        raw = JsonValue.of(Map.of("model", JsonValue.of(response.modelName() == null ? "" : response.modelName())));
                    }
                    events.add(new ModelEvent.Finished(reason, usage, raw));
                } finally {
                    done.countDown();
                }
            }

            @Override
            public void onError(Throwable error) {
                try {
                    failure.set(error);
                    String message = error == null ? "provider error" : (error.getMessage() == null ? error.toString() : error.getMessage());
                    events.add(new ModelEvent.ProviderFailed(classify(error), message, Option.none(), Option.none()));
                } finally {
                    done.countDown();
                }
            }
        };

        try {
            StreamingChatModel model = streaming != null ? streaming : internalStreaming;
            if (model != null) {
                model.chat(lcRequest, handler);
            } else if (chat != null) {
                ChatResponse response = chat.chat(lcRequest);
                handler.onCompleteResponse(response);
            } else {
                handler.onError(new IllegalStateException("no chat model configured"));
            }
        } catch (Throwable t) {
            handler.onError(t);
        }

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            events.add(new ModelEvent.ProviderFailed(new FailureKind.Timeout(), "interrupted", Option.none(), Option.none()));
        }

        if (failure.get() != null && events.stream().noneMatch(e -> e instanceof ModelEvent.ProviderFailed)) {
            Throwable t = failure.get();
            String message = t.getMessage() == null ? t.toString() : t.getMessage();
            events.add(new ModelEvent.ProviderFailed(classify(t), message, Option.none(), Option.none()));
        }
        REQUEST_HEADERS.remove();
        return events.stream();
    }

    private CallId mapToolCallId(String lcId) {
        CallId generated = CallId.create();
        if (lcId != null && !lcId.isBlank()) {
            originalToolCallIds.put(generated, lcId);
        }
        return generated;
    }

    private ChatRequest toChatRequest(ModelRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        for (String system : request.system()) {
            if (system != null && !system.isBlank()) {
                messages.add(SystemMessage.from(system));
            }
        }
        for (ModelMessage message : request.messages()) {
            switch (message) {
                case ModelMessage.User user -> messages.add(UserMessage.from(joinText(user.content())));
                case ModelMessage.Assistant assistant -> {
                    String text = assistant.content().stream()
                            .filter(c -> c instanceof ModelContent.Text)
                            .map(c -> ((ModelContent.Text) c).text())
                            .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
                    List<ToolExecutionRequest> toolCalls = assistant.content().stream()
                            .filter(c -> c instanceof ModelContent.ToolCall)
                            .map(c -> {
                                ModelContent.ToolCall tc = (ModelContent.ToolCall) c;
                                String args = Json.encode(tc.input());
                                return ToolExecutionRequest.builder()
                                        .id(originalId(tc.callId()))
                                        .name(tc.name().value())
                                        .arguments(args)
                                        .build();
                            })
                            .toList();
                    messages.add(AiMessage.from(text, toolCalls));
                }
                case ModelMessage.ToolResult result -> {
                    String content = outcomeText(result.outcome());
                    messages.add(ToolExecutionResultMessage.from(
                            originalId(result.callId()), result.name().value(), content));
                }
            }
        }

        String configuredModel = System.getenv().getOrDefault("ENGINE_MODEL_ID",
                System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"));
        if (configuredModel == null || configuredModel.isBlank()) configuredModel = "gpt-4o-mini";
        DefaultChatRequestParameters.Builder<?> params = new DefaultChatRequestParameters.Builder<>();
        params.modelName(configuredModel);
        request.generation().temperature().ifPresent(params::temperature);
        request.generation().topP().ifPresent(params::topP);
        request.generation().maxOutputTokens().ifPresent(v -> params.maxOutputTokens(v));
        if (!request.generation().stop().isEmpty()) {
            params.stopSequences(request.generation().stop());
        }
        if (!request.tools().isEmpty()) {
            params.toolSpecifications(request.tools().stream().map(this::toToolSpecification).toList());
        }
        switch (request.toolChoice()) {
            case Option.None<?> ignored -> params.toolChoice(ToolChoice.AUTO);
            case Option.Some<?> some -> {
                if (some.value() instanceof Choice.None) params.toolChoice(ToolChoice.NONE);
                else if (some.value() instanceof Choice.Required) params.toolChoice(ToolChoice.REQUIRED);
                else if (some.value() instanceof Choice.One one) params.toolChoice(ToolChoice.REQUIRED);
                else params.toolChoice(ToolChoice.AUTO);
            }
        }

        return ChatRequest.builder()
                .messages(messages)
                .parameters(params.build())
                .build();
    }

    private String originalId(CallId callId) {
        String original = originalToolCallIds.get(callId);
        return original == null ? callId.value() : original;
    }

    private ToolSpecification toToolSpecification(ToolDefinition definition) {
        return ToolSpecification.builder()
                .name(definition.effectiveName())
                .description(definition.description())
                .parameters(toJsonObjectSchema(definition.inputSchema()))
                .build();
    }

    private JsonObjectSchema toJsonObjectSchema(Schema schema) {
        if (schema instanceof Schema.Obj obj) {
            JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
            List<String> required = new ArrayList<>();
            for (Map.Entry<String, Schema> field : obj.fields().entrySet()) {
                Schema inner = field.getValue();
                boolean optional = inner instanceof Schema.Opt;
                Schema unwrapped = optional ? ((Schema.Opt) inner).inner() : inner;
                builder.addProperty(field.getKey(), toLcSchema(unwrapped));
                if (!optional) required.add(field.getKey());
            }
            if (obj.additionalProperties()) builder.additionalProperties(true);
            return builder.required(required).build();
        }
        // Wrap non-object schemas so ToolSpecification always receives an object schema.
        return JsonObjectSchema.builder()
                .addProperty("value", toLcSchema(schema))
                .required("value")
                .build();
    }

    private JsonSchemaElement toLcSchema(Schema schema) {
        return switch (schema) {
            case Schema.Str ignored -> new JsonStringSchema();
            case Schema.Int ignored -> new JsonIntegerSchema();
            case Schema.Long ignored -> new JsonIntegerSchema();
            case Schema.Num ignored -> new JsonNumberSchema();
            case Schema.Bool ignored -> new JsonBooleanSchema();
            case Schema.Arr arr -> JsonArraySchema.builder().items(toLcSchema(arr.item())).build();
            case Schema.Obj obj -> toJsonObjectSchema(obj);
            case Schema.Opt opt -> toLcSchema(opt.inner());
            case Schema.Enum en -> {
                JsonEnumSchema.Builder b = JsonEnumSchema.builder();
                b.enumValues(en.values());
                yield b.build();
            }
            case Schema.Union union -> {
                JsonAnyOfSchema.Builder b = JsonAnyOfSchema.builder();
                for (Schema option : union.options()) {
                    b.anyOf(toLcSchema(option));
                }
                yield b.build();
            }
            case Schema.Any ignored -> new JsonStringSchema();
        };
    }

    private String joinText(List<ModelContent> content) {
        StringBuilder sb = new StringBuilder();
        for (ModelContent item : content) {
            if (item instanceof ModelContent.Text text) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    private String outcomeText(OutcomePayload outcome) {
        return switch (outcome) {
            case OutcomePayload.Text text -> text.text();
            case OutcomePayload.Json json -> Json.encode(json.value());
            case OutcomePayload.Content content -> {
                StringBuilder sb = new StringBuilder();
                for (var item : content.items()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(item.toString());
                }
                yield sb.toString();
            }
        };
    }

    private FinishReason mapFinishReason(dev.langchain4j.model.output.FinishReason reason) {
        if (reason == null) return FinishReason.UNKNOWN;
        return switch (reason) {
            case STOP -> FinishReason.NORMAL;
            case LENGTH -> FinishReason.LENGTH_LIMIT;
            case CONTENT_FILTER -> FinishReason.CONTENT_FILTER;
            case TOOL_EXECUTION -> FinishReason.NORMAL;
            case OTHER -> FinishReason.UNKNOWN;
        };
    }

    private TokenCount mapUsage(TokenUsage usage) {
        if (usage == null) return TokenCount.zero();
        long input = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        long output = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        return TokenCount.of(input, output);
    }

    private JsonValue parseJson(String text) {
        if (text == null || text.isBlank()) return JsonValue.ofNull();
        var result = Json.parse(text);
        if (result instanceof de.augmentia.oc.engine.core.json.Result.Ok<JsonValue> ok) {
            return ok.value();
        }
        return JsonValue.of(text);
    }

    private FailureKind classify(Throwable error) {
        String message = error == null ? "" : (error.getMessage() == null ? error.toString() : error.getMessage());
        String lower = message.toLowerCase();
        if (lower.contains("rate limit") || lower.contains("429")) return new FailureKind.RateLimited(Option.none());
        if (lower.contains("auth") || lower.contains("api key") || lower.contains("401") || lower.contains("403")) {
            return new FailureKind.Authentication(Option.none());
        }
        if (lower.contains("quota")) return new FailureKind.QuotaExceeded(Option.none());
        if (lower.contains("content") && lower.contains("filter")) return new FailureKind.ContentPolicy();
        if (lower.contains("context") && (lower.contains("length") || lower.contains("too long") || lower.contains("overflow"))) {
            return new FailureKind.InvalidRequest(InvalidRequestClassification.CONTEXT_OVERFLOW);
        }
        if (lower.contains("timeout")) return new FailureKind.Timeout();
        return new FailureKind.ProviderInternal(Option.none());
    }
}
