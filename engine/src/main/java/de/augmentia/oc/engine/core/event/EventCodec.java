package de.augmentia.oc.engine.core.event;

import de.augmentia.oc.engine.core.json.DecodeError;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Payload;
import de.augmentia.oc.engine.core.model.SessionEvent;

/**
 * Encodes and decodes durable events. Every event carries a version; the
 * upgrade of old shapes happens here and nowhere else (Java-Impl S-5).
 */
public final class EventCodec {

    private final PayloadRegistry registry;

    public EventCodec(PayloadRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry must not be null");
        this.registry = registry;
    }

    public static EventCodec standard() {
        PayloadRegistry registry = new PayloadRegistry();
        register(registry, SessionEvent.SessionCreated.class);
        register(registry, SessionEvent.SessionRenamed.class);
        register(registry, SessionEvent.SessionArchived.class);
        register(registry, SessionEvent.SessionUnarchived.class);
        register(registry, SessionEvent.SessionMetadataUpdated.class);
        register(registry, SessionEvent.SessionPermissions.class);
        register(registry, SessionEvent.SessionAgentSelected.class);
        register(registry, SessionEvent.SessionModelSelected.class);
        register(registry, SessionEvent.SessionViewed.class);
        register(registry, SessionEvent.SessionMoved.class);
        register(registry, SessionEvent.SessionForked.class);
        register(registry, SessionEvent.SessionDeleted.class);
        register(registry, SessionEvent.InboxEnqueued.class);
        register(registry, SessionEvent.InboxDelivered.class);
        register(registry, SessionEvent.InboxCancelled.class);
        register(registry, SessionEvent.InboxDeliveryChanged.class);
        register(registry, SessionEvent.InstructionsUpdated.class);
        register(registry, SessionEvent.SyntheticRaised.class);
        register(registry, SessionEvent.SkillActivated.class);
        register(registry, SessionEvent.ShellStarted.class);
        register(registry, SessionEvent.ShellEnded.class);
        register(registry, SessionEvent.StepStarted.class);
        register(registry, SessionEvent.StepStreamed.class);
        register(registry, SessionEvent.StepEnded.class);
        register(registry, SessionEvent.StepFailed.class);
        register(registry, SessionEvent.TextStarted.class);
        register(registry, SessionEvent.TextEnded.class);
        register(registry, SessionEvent.ReasoningStarted.class);
        register(registry, SessionEvent.ReasoningEnded.class);
        register(registry, SessionEvent.ToolInputStarted.class);
        register(registry, SessionEvent.ToolInputEnded.class);
        register(registry, SessionEvent.ToolCalled.class);
        register(registry, SessionEvent.ToolSucceeded.class);
        register(registry, SessionEvent.ToolFailed.class);
        register(registry, SessionEvent.ExecutionStarted.class);
        register(registry, SessionEvent.ExecutionSucceeded.class);
        register(registry, SessionEvent.ExecutionFailed.class);
        register(registry, SessionEvent.ExecutionInterrupted.class);
        register(registry, SessionEvent.CompactionStarted.class);
        register(registry, SessionEvent.CompactionEnded.class);
        register(registry, SessionEvent.CompactionFailed.class);
        register(registry, SessionEvent.RevertStaged.class);
        register(registry, SessionEvent.RevertCleared.class);
        register(registry, SessionEvent.RevertCommitted.class);
        register(registry, SessionEvent.RetryScheduled.class);
        register(registry, SessionEvent.UsageRecorded.class);
        return new EventCodec(registry);
    }

    private static void register(PayloadRegistry registry, Class<? extends Payload> payloadType) {
        registry.register(payloadType.getSimpleName(), payloadType);
    }

    public PayloadRegistry registry() {
        return registry;
    }

    public JsonValue encode(Payload payload) {
        if (payload == null) throw new IllegalArgumentException("payload must not be null");
        String type = payload.getClass().getSimpleName();
        if (registry.codec(type).isPresent()) {
            return registry.codec(type).orElseThrow().encode(payload);
        }
        if (registry.type(type).isEmpty()) {
            throw new IllegalArgumentException("no payload type registered for " + type);
        }
        return ContractCodec.encode(payload);
    }

    public Result<Payload> decode(String type, int version, JsonValue value) {
        return registry.decode(type, version, value);
    }

    /**
     * Lifts an old event shape to the current version. Without a registered
     * upgradder only the current version can be decoded.
     */
    public Result<Payload> upgrade(String type, int version, JsonValue value) {
        if (version == 1) {
            return decode(type, version, value);
        }
        return new Result.Err<>(new DecodeError("$",
                "no upgrade path from version " + version + " for " + type));
    }
}
