package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates OpenAI Chat Completions SSE chunks into normalized events.
 * Finish/usage arrive before {@code [DONE]}, so the terminal event is emitted
 * when the stream ends.
 */
public final class OpenAiEventTranslator implements EventTranslator {

    private static final String TEXT_BLOCK = "text-1";
    private static final String REASONING_BLOCK = "reasoning-1";

    private final StringBuilder text = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, ToolState> tools = new LinkedHashMap<>();
    private final Map<String, CallId> callIds = new LinkedHashMap<>();
    private boolean textStarted;
    private boolean textEnded;
    private boolean reasoningStarted;
    private boolean reasoningEnded;
    private boolean done;
    private FinishReason finish = FinishReason.UNKNOWN;
    private TokenCount usage = TokenCount.zero();

    @Override
    public List<ModelEvent> translate(SseReader.SseFrame frame) {
        if (frame == null) throw new IllegalArgumentException("frame must not be null");
        String data = frame.data().trim();
        if (data.isEmpty()) return List.of();
        if ("[DONE]".equals(data)) {
            if (done) return List.of();
            done = true;
            return finish();
        }

        Result<JsonValue> parsed = Json.parse(data);
        if (parsed instanceof Result.Err<JsonValue> error) {
            return List.of(failure(new FailureKind.InvalidOutput(InvalidOutputClassification.UNKNOWN),
                    "malformed chunk: " + error.error().message()));
        }
        JsonValue value = parsed.valueOrNull();
        if (!(value instanceof JsonValue.Obj object)) return List.of();

        if (object.values().get("error") instanceof JsonValue.Obj error) {
            return List.of(failure(kind(error), string(error, "message")));
        }

        if (object.values().get("usage") instanceof JsonValue.Obj usageObject) {
            usage = TokenCount.of(number(usageObject, "prompt_tokens"), number(usageObject, "completion_tokens"));
        }

        if (!(object.values().get("choices") instanceof JsonValue.Arr choices) || choices.values().isEmpty()) {
            return List.of();
        }
        JsonValue choice = choices.values().getFirst();
        if (!(choice instanceof JsonValue.Obj choiceObject)) return List.of();

        List<ModelEvent> events = new ArrayList<>();
        if (choiceObject.values().get("delta") instanceof JsonValue.Obj delta) {
            if (delta.values().get("content") instanceof JsonValue.Str content && !content.value().isEmpty()) {
                if (!textStarted) {
                    textStarted = true;
                    events.add(new ModelEvent.TextStarted(TEXT_BLOCK));
                }
                text.append(content.value());
                events.add(new ModelEvent.TextDelta(TEXT_BLOCK, content.value()));
            }
            if (delta.values().get("reasoning_content") instanceof JsonValue.Str reason
                    && !reason.value().isEmpty()) {
                if (!reasoningStarted) {
                    reasoningStarted = true;
                    events.add(new ModelEvent.ReasoningStarted(REASONING_BLOCK));
                }
                reasoning.append(reason.value());
                events.add(new ModelEvent.ReasoningDelta(REASONING_BLOCK, reason.value()));
            }
            if (delta.values().get("tool_calls") instanceof JsonValue.Arr calls) {
                events.addAll(toolCalls(calls));
            }
        }
        if (choiceObject.values().get("finish_reason") instanceof JsonValue.Str reason) {
            finish = finishReason(reason.value());
        }
        return List.copyOf(events);
    }

    private List<ModelEvent> toolCalls(JsonValue.Arr calls) {
        List<ModelEvent> events = new ArrayList<>();
        for (JsonValue item : calls.values()) {
            if (!(item instanceof JsonValue.Obj call)) continue;
            int index = (int) number(call, "index");
            ToolState state = tools.computeIfAbsent(index, ignored -> new ToolState());
            if (call.values().get("id") instanceof JsonValue.Str id) {
                state.callId = callIds.computeIfAbsent(id.value(), ignored -> CallId.create());
            }
            if (call.values().get("function") instanceof JsonValue.Obj function) {
                String name = string(function, "name");
                if (name != null && !name.isBlank() && !state.started) {
                    state.name = new ToolName(name);
                    state.started = true;
                    if (state.callId == null) state.callId = CallId.create();
                    events.add(new ModelEvent.ToolInputStarted(state.callId, state.name));
                }
                String arguments = string(function, "arguments");
                if (arguments != null && !arguments.isEmpty()) {
                    state.arguments.append(arguments);
                    if (state.started) {
                        events.add(new ModelEvent.ToolInputDelta(state.callId, arguments));
                    }
                }
            }
        }
        return events;
    }

    private List<ModelEvent> finish() {
        List<ModelEvent> events = new ArrayList<>();
        if (textStarted && !textEnded) {
            textEnded = true;
            events.add(new ModelEvent.TextEnded(TEXT_BLOCK, text.toString()));
        }
        if (reasoningStarted && !reasoningEnded) {
            reasoningEnded = true;
            events.add(new ModelEvent.ReasoningEnded(REASONING_BLOCK, reasoning.toString()));
        }
        for (ToolState state : tools.values()) {
            if (!state.started || state.ended) continue;
            state.ended = true;
            events.add(new ModelEvent.ToolInputEnded(state.callId, state.arguments.toString()));
            Result<JsonValue> input = Json.parse(state.arguments.isEmpty() ? "{}" : state.arguments.toString());
            if (input instanceof Result.Ok<JsonValue> ok) {
                events.add(new ModelEvent.ToolCallRequested(state.callId, state.name, ok.value()));
            } else {
                events.add(new ModelEvent.ToolFailed(state.callId, "malformed tool input"));
            }
        }
        events.add(new ModelEvent.Finished(finish, usage, JsonValue.ofNull()));
        return List.copyOf(events);
    }

    private static ModelEvent.ProviderFailed failure(FailureKind kind, String message) {
        return new ModelEvent.ProviderFailed(kind, message == null ? "" : message,
                Option.none(), Option.none());
    }

    private static FailureKind kind(JsonValue.Obj error) {
        String code = string(error, "code");
        String type = string(error, "type");
        String signal = (code == null ? "" : code) + " " + (type == null ? "" : type);
        if (signal.contains("rate_limit")) return new FailureKind.RateLimited(Option.none());
        if (signal.contains("context_length") || signal.contains("context_window")) {
            return new FailureKind.InvalidRequest(InvalidRequestClassification.CONTEXT_OVERFLOW);
        }
        if (signal.contains("auth") || signal.contains("api_key")) {
            return new FailureKind.Authentication(Option.none());
        }
        if (signal.contains("invalid_request")) {
            return new FailureKind.InvalidRequest(InvalidRequestClassification.OTHER);
        }
        return new FailureKind.ProviderInternal(Option.none());
    }

    private static FinishReason finishReason(String value) {
        return switch (value) {
            case "stop", "tool_calls" -> FinishReason.NORMAL;
            case "length" -> FinishReason.LENGTH_LIMIT;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.UNKNOWN;
        };
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
    }

    private static long number(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Num num ? num.value().longValue() : 0;
    }

    private static final class ToolState {
        private CallId callId;
        private ToolName name;
        private final StringBuilder arguments = new StringBuilder();
        private boolean started;
        private boolean ended;
    }
}
