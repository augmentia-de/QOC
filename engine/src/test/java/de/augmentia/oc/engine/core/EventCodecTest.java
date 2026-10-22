package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.event.EventCodec;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.*;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventCodecTest {

    private final EventCodec codec = EventCodec.standard();

    @Test
    void roundTripsEveryEventVariant() {
        for (SessionEvent event : samples()) {
            JsonValue encoded = codec.encode(event);
            Result<Payload> decoded = codec.decode(event.getClass().getSimpleName(), 1, encoded);
            assertThat(decoded).as(event.getClass().getSimpleName()).isInstanceOf(Result.Ok.class);
            assertThat(decoded.valueOrNull()).as(event.getClass().getSimpleName()).isEqualTo(event);
        }
    }

    @Test
    void decodedEventCanBeEncodedAgain() {
        for (SessionEvent event : samples()) {
            JsonValue once = codec.encode(event);
            Payload decoded = codec.decode(event.getClass().getSimpleName(), 1, once).valueOrNull();
            assertThat(codec.encode(decoded)).isEqualTo(once);
        }
    }

    @Test
    void unknownTypeFails() {
        Result<Payload> decoded = codec.decode("NoSuchEvent", 1, JsonValue.ofNull());
        assertThat(decoded).isInstanceOf(Result.Err.class);
    }

    @Test
    void unknownVersionFails() {
        Result<Payload> decoded = codec.decode("ExecutionStarted", 7, JsonValue.ofNull());
        assertThat(decoded).isInstanceOf(Result.Err.class);
    }

    @Test
    void upgradeWithoutPathFails() {
        Result<Payload> upgraded = codec.upgrade("ExecutionStarted", 0, JsonValue.ofNull());
        assertThat(upgraded).isInstanceOf(Result.Err.class);
    }

    @Test
    void upgradeOfCurrentVersionDecodes() {
        SessionEvent event = new SessionEvent.ExecutionStarted(SessionId.create());
        Result<Payload> upgraded = codec.upgrade("ExecutionStarted", 1, codec.encode(event));
        assertThat(upgraded).isInstanceOf(Result.Ok.class);
        assertThat(upgraded.valueOrNull()).isEqualTo(event);
    }

    @Test
    void everySealedEventVariantIsRegistered() {
        for (Class<?> subtype : SessionEvent.class.getPermittedSubclasses()) {
            assertThat(codec.registry().type(subtype.getSimpleName()).isPresent())
                    .as(subtype.getSimpleName())
                    .isTrue();
        }
    }

    private static List<SessionEvent> samples() {
        SessionId session = SessionId.create();
        MessageId message = MessageId.create();
        AgentId agent = AgentId.create();
        ModelRef model = ModelRef.of(ProviderId.create(), ModelId.create());
        Location location = new Location(Path.of("/tmp/project"), ProjectId.create(), Option.none());

        return List.of(
                new SessionEvent.SessionCreated(session, location, agent, Option.some(model), Instant.EPOCH),
                new SessionEvent.SessionRenamed(session, "title"),
                new SessionEvent.SessionArchived(session),
                new SessionEvent.SessionUnarchived(session),
                new SessionEvent.SessionMetadataUpdated(session, Map.of("key", "value")),
                new SessionEvent.SessionPermissions(session, List.of(new Rule("read", "*", Effect.ALLOW))),
                new SessionEvent.SessionAgentSelected(session, agent, Option.none()),
                new SessionEvent.SessionModelSelected(session, model, Option.some(model)),
                new SessionEvent.SessionViewed(session, Instant.EPOCH),
                new SessionEvent.SessionMoved(session, location),
                new SessionEvent.SessionForked(session, SessionId.create(), new ForkBoundary(message)),
                new SessionEvent.SessionDeleted(session),
                new SessionEvent.InboxEnqueued(session, InboxId.create(),
                        new InboxItem.UserItem("hello", List.of(), List.of(), List.of(), Map.of()), Seq.of(3)),
                new SessionEvent.InboxDelivered(session, InboxId.create()),
                new SessionEvent.InboxCancelled(session, InboxId.create()),
                new SessionEvent.InboxDeliveryChanged(session, InboxId.create(), Delivery.STEER),
                new SessionEvent.InstructionsUpdated(session, Map.of("a", Option.some("b")), Option.some("text")),
                new SessionEvent.SyntheticRaised(session, "text", Option.none(), Map.of()),
                new SessionEvent.SkillActivated(session, SkillId.create(), "skill", "body"),
                new SessionEvent.ShellStarted(session, new ShellInfo("sh1", "ls", "running", Option.none())),
                new SessionEvent.ShellEnded(session, new ShellInfo("sh1", "ls", "done", Option.some(0)), "out"),
                new SessionEvent.StepStarted(session, message, agent, model, Instant.EPOCH),
                new SessionEvent.StepStreamed(session, message),
                new SessionEvent.StepEnded(session, message, FinishReason.NORMAL, Money.of(2), TokenCount.of(3, 4)),
                new SessionEvent.StepFailed(session, message,
                        new SessionError.ProviderError("rate", "slow", 429, "body", null),
                        Money.zero(), TokenCount.zero()),
                new SessionEvent.TextStarted(session, message),
                new SessionEvent.TextEnded(session, message, "text"),
                new SessionEvent.ReasoningStarted(session, message),
                new SessionEvent.ReasoningEnded(session, message, "reasoning"),
                new SessionEvent.ToolInputStarted(session, message, CallId.create(), new ToolName("read")),
                new SessionEvent.ToolInputEnded(session, message, CallId.create(), "{\"path\":\"a\"}"),
                new SessionEvent.ToolCalled(session, message, CallId.create(), new ToolName("read"),
                        JsonValue.of(Map.of("path", JsonValue.of("a.txt"))), false),
                new SessionEvent.ToolSucceeded(session, message, CallId.create(),
                        List.of(new ToolContent.TextItem("hello"),
                                new ToolContent.FileItem("file:///a", "text/plain", Option.some("a"))),
                        Map.of("truncated", "false"), true),
                new SessionEvent.ToolFailed(session, message, CallId.create(),
                        new SessionError.ToolExecutionError("io", "failed"), Map.of(), false),
                new SessionEvent.ExecutionStarted(session),
                new SessionEvent.ExecutionSucceeded(session),
                new SessionEvent.ExecutionFailed(session, new SessionError.Unknown("boom", "message")),
                new SessionEvent.ExecutionInterrupted(session, InterruptReason.USER),
                new SessionEvent.CompactionStarted(session, CompactionReason.OVERFLOW, Option.none()),
                new SessionEvent.CompactionEnded(session, CompactionReason.OVERFLOW, "summary", model,
                        Money.of(1), TokenCount.of(2, 3)),
                new SessionEvent.CompactionFailed(session, CompactionReason.MANUAL,
                        new SessionError.CompactionError("x", "y"), Option.none()),
                new SessionEvent.RevertStaged(session, message),
                new SessionEvent.RevertCleared(session),
                new SessionEvent.RevertCommitted(session, Option.some(message)),
                new SessionEvent.RetryScheduled(session, message, 2, Instant.EPOCH,
                        new SessionError.Unknown("retry", "again")),
                new SessionEvent.UsageRecorded(session, Money.of("0.5"), TokenCount.of(1, 1)));
    }
}
