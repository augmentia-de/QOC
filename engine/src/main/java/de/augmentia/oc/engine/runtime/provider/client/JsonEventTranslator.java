package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.contract.provider.TransportDelivery;
import de.augmentia.oc.engine.core.contract.provider.TransportOperation;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;

import java.time.Duration;
import java.util.List;

/**
 * Translates the normalized wire shape used by the adapter tests. Provider
 * specific encodings plug in by implementing {@link EventTranslator}.
 */
public final class JsonEventTranslator implements EventTranslator {

    @Override
    public List<ModelEvent> translate(SseReader.SseFrame frame) {
        if (frame == null) throw new IllegalArgumentException("frame must not be null");
        String data = frame.data().trim();
        if (data.isEmpty()) return List.of();
        if ("[DONE]".equals(data)) return List.of();

        Result<JsonValue> parsed = Json.parse(data);
        if (parsed instanceof Result.Err<JsonValue> error) {
            return List.of(failure(new FailureKind.InvalidOutput(InvalidOutputClassification.UNKNOWN),
                    "malformed event: " + error.error().message()));
        }
        JsonValue value = parsed.valueOrNull();
        String type = string(value, "type");
        if (type == null) {
            return List.of(failure(new FailureKind.InvalidOutput(InvalidOutputClassification.UNKNOWN),
                    "event without type"));
        }

        try {
            return switch (type) {
                case "text.started" -> List.of(new ModelEvent.TextStarted(string(value, "blockId")));
                case "text.delta" -> List.of(new ModelEvent.TextDelta(string(value, "blockId"), text(value)));
                case "text.ended" -> List.of(new ModelEvent.TextEnded(string(value, "blockId"), text(value)));
                case "reasoning.started" -> List.of(new ModelEvent.ReasoningStarted(string(value, "blockId")));
                case "reasoning.delta" -> List.of(new ModelEvent.ReasoningDelta(string(value, "blockId"), text(value)));
                case "reasoning.ended" -> List.of(new ModelEvent.ReasoningEnded(string(value, "blockId"), text(value)));
                case "tool.input.started" -> List.of(new ModelEvent.ToolInputStarted(
                        callId(value), new ToolName(string(value, "name"))));
                case "tool.input.delta" -> List.of(new ModelEvent.ToolInputDelta(
                        callId(value), text(value)));
                case "tool.input.ended" -> List.of(new ModelEvent.ToolInputEnded(
                        callId(value), text(value)));
                case "tool.call" -> List.of(new ModelEvent.ToolCallRequested(
                        callId(value), new ToolName(string(value, "name")), field(value, "input")));
                case "tool.result" -> List.of(new ModelEvent.ToolResultProvided(
                        callId(value), outcome(field(value, "outcome")), bool(value, "providerExecuted")));
                case "tool.failed" -> List.of(new ModelEvent.ToolFailed(callId(value), text(value)));
                case "finished" -> List.of(new ModelEvent.Finished(
                        finishReason(string(value, "reason")), usage(field(value, "usage")),
                        field(value, "raw")));
                case "provider.failed" -> List.of(new ModelEvent.ProviderFailed(
                        failureKind(value), string(value, "message"), optionInt(value, "status"),
                        optionDuration(value, "retryAfter")));
                default -> List.of(failure(new FailureKind.InvalidOutput(InvalidOutputClassification.UNKNOWN),
                        "unknown event type: " + type));
            };
        } catch (IllegalArgumentException e) {
            return List.of(failure(new FailureKind.InvalidOutput(InvalidOutputClassification.UNKNOWN),
                    "malformed event: " + e.getMessage()));
        }
    }

    private static FailureKind failureKind(JsonValue value) {
        String kind = string(value, "kind");
        if (kind == null) kind = "unknown";
        Option<Duration> retryAfter = optionDuration(value, "retryAfter");
        return switch (kind) {
            case "rate_limited" -> new FailureKind.RateLimited(retryAfter);
            case "provider_internal" -> new FailureKind.ProviderInternal(retryAfter);
            case "transport" -> new FailureKind.Transport(
                    delivery(string(value, "delivery")), operation(string(value, "operation")),
                    string(value, "code") == null ? "" : string(value, "code"));
            case "invalid_output" -> new FailureKind.InvalidOutput(
                    "incomplete_stream".equals(string(value, "classification"))
                            ? InvalidOutputClassification.INCOMPLETE_STREAM
                            : InvalidOutputClassification.UNKNOWN);
            case "invalid_request" -> new FailureKind.InvalidRequest(
                    "context_overflow".equals(string(value, "classification"))
                            ? InvalidRequestClassification.CONTEXT_OVERFLOW
                            : InvalidRequestClassification.OTHER);
            case "authentication" -> new FailureKind.Authentication(optionString(value, "hint"));
            case "quota_exceeded" -> new FailureKind.QuotaExceeded(optionString(value, "hint"));
            case "content_policy" -> new FailureKind.ContentPolicy();
            case "unsupported_operation" -> new FailureKind.UnsupportedOperation(
                    string(value, "operation") == null ? "" : string(value, "operation"));
            case "no_route" -> new FailureKind.NoRoute();
            case "unknown_provider" -> new FailureKind.UnknownProvider();
            case "timeout" -> new FailureKind.Timeout();
            default -> new FailureKind.InvalidOutput(InvalidOutputClassification.UNKNOWN);
        };
    }

    private static TransportDelivery delivery(String value) {
        return switch (value == null ? "" : value) {
            case "accepted" -> TransportDelivery.ACCEPTED;
            case "rejected" -> TransportDelivery.REJECTED;
            default -> TransportDelivery.UNSENT;
        };
    }

    private static TransportOperation operation(String value) {
        return "read".equals(value) ? TransportOperation.READ : TransportOperation.SEND;
    }

    private static FinishReason finishReason(String value) {
        return switch (value == null ? "" : value) {
            case "normal" -> FinishReason.NORMAL;
            case "length" -> FinishReason.LENGTH_LIMIT;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            case "aborted" -> FinishReason.ABORTED;
            default -> FinishReason.UNKNOWN;
        };
    }

    private static TokenCount usage(JsonValue value) {
        if (!(value instanceof JsonValue.Obj object)) return TokenCount.zero();
        long input = number(object.values().get("input"), 0);
        long output = number(object.values().get("output"), 0);
        return TokenCount.of(Math.max(0, input), Math.max(0, output));
    }

    private static OutcomePayload outcome(JsonValue value) {
        if (!(value instanceof JsonValue.Obj object)) return new OutcomePayload.Text("");
        String type = string(value, "type");
        if ("json".equals(type)) return new OutcomePayload.Json(field(value, "value"));
        if ("content".equals(type)) return new OutcomePayload.Content(List.of(), Option.none());
        return new OutcomePayload.Text(text(value));
    }

    private static ModelEvent.ProviderFailed failure(FailureKind kind, String message) {
        return new ModelEvent.ProviderFailed(kind, message, Option.none(), Option.none());
    }

    private static String text(JsonValue value) {
        String text = string(value, "text");
        return text == null ? "" : text;
    }

    private static CallId callId(JsonValue value) {
        String id = string(value, "callId");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("missing callId");
        return CallId.parse(id);
    }

    private static String string(JsonValue value, String key) {
        if (value instanceof JsonValue.Obj object
                && object.values().get(key) instanceof JsonValue.Str str) {
            return str.value();
        }
        return null;
    }

    private static JsonValue field(JsonValue value, String key) {
        if (value instanceof JsonValue.Obj object && object.values().containsKey(key)) {
            return object.values().get(key);
        }
        return JsonValue.ofNull();
    }

    private static Option<String> optionString(JsonValue value, String key) {
        return Option.of(string(value, key));
    }

    private static Option<Integer> optionInt(JsonValue value, String key) {
        if (value instanceof JsonValue.Obj object && object.values().get(key) instanceof JsonValue.Num num) {
            return Option.some(num.value().intValue());
        }
        return Option.none();
    }

    private static Option<Duration> optionDuration(JsonValue value, String key) {
        if (value instanceof JsonValue.Obj object && object.values().get(key) instanceof JsonValue.Num num) {
            return Option.some(Duration.ofMillis(num.value().longValue()));
        }
        return Option.none();
    }

    private static long number(JsonValue value, long fallback) {
        return value instanceof JsonValue.Num num ? num.value().longValue() : fallback;
    }

    private static boolean bool(JsonValue value, String key) {
        return value instanceof JsonValue.Obj object
                && object.values().get(key) instanceof JsonValue.Bool b && b.value();
    }
}
