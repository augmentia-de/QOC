package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;

import java.time.Duration;

public sealed interface ModelEvent {

    record TextStarted(String blockId) implements ModelEvent {}

    record TextDelta(String blockId, String text) implements ModelEvent {}

    record TextEnded(String blockId, String text) implements ModelEvent {}

    record ReasoningStarted(String blockId) implements ModelEvent {}

    record ReasoningDelta(String blockId, String text) implements ModelEvent {}

    record ReasoningEnded(String blockId, String text) implements ModelEvent {}

    record ToolInputStarted(CallId callId, ToolName name) implements ModelEvent {}

    record ToolInputDelta(CallId callId, String text) implements ModelEvent {}

    record ToolInputEnded(CallId callId, String text) implements ModelEvent {}

    record ToolCallRequested(CallId callId, ToolName name, JsonValue input) implements ModelEvent {}

    record ToolResultProvided(CallId callId, OutcomePayload outcome, boolean providerExecuted) implements ModelEvent {}

    record LlmRequest(String endpoint, String body) implements ModelEvent {}

    record LlmResponse(String body) implements ModelEvent {}

    record ToolFailed(CallId callId, String message) implements ModelEvent {}

    record Finished(FinishReason reason, TokenCount usage, JsonValue raw) implements ModelEvent {
        public Finished {
            if (reason == null) reason = FinishReason.UNKNOWN;
            if (usage == null) usage = TokenCount.zero();
            if (raw == null) raw = JsonValue.ofNull();
        }
    }

    record RetryScheduled(int attempt, Duration delay, FailureKind kind) implements ModelEvent {
        public RetryScheduled {
            if (delay == null) delay = Duration.ZERO;
            if (kind == null) kind = new FailureKind.Transport(
                    de.augmentia.oc.engine.core.contract.provider.TransportDelivery.UNSENT,
                    de.augmentia.oc.engine.core.contract.provider.TransportOperation.SEND, "retry");
        }
    }

    record ProviderFailed(FailureKind kind, String message, Option<Integer> status,
                          Option<Duration> retryAfter) implements ModelEvent {
        public ProviderFailed {
            if (kind == null) throw new IllegalArgumentException("kind must not be null");
            if (message == null) message = "";
            if (status == null) status = Option.none();
            if (retryAfter == null) retryAfter = Option.none();
        }
    }
}