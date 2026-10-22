package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.Modality;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.event.ContractCodec;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContractRoundTripTest {

    @Test
    void roundTripsEveryMessageVariant() {
        MessageId id = MessageId.create();
        ModelRef model = ModelRef.of(ProviderId.create(), ModelId.create());

        assertRoundTrip(new Message.UserMessage(id, "text", List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.SyntheticMessage(id, "text", Option.some("d"), Map.of(), Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.SystemMessage(id, "text", Option.none(), Map.of(), Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.SkillMessage(id, SkillId.create(), "skill", "body", Map.of(), Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.ShellMessage(id, new ShellInfo("s", "ls", "done", Option.some(0)), Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.AssistantMessage(id, AgentId.create(), model,
                List.of(new Content.TextContent("hi", Instant.EPOCH)), Option.some(FinishReason.NORMAL),
                Option.none(), Option.none(), Option.none(), Money.zero(), TokenCount.zero(),
                AssistantTime.started(Instant.EPOCH)), Message.class);
        assertRoundTrip(new Message.CompactionMessage(id, CompactionStatus.COMPLETED,
                CompactionReason.MANUAL, "summary", Option.none(), Money.zero(), TokenCount.zero(),
                Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.AgentSwitched(id, AgentId.create(), AgentId.create(), Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.ModelSwitched(id, model, model, Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.LocationSwitched(id,
                new Location(java.nio.file.Path.of("/a"), ProjectId.create(), Option.none()),
                new Location(java.nio.file.Path.of("/b"), ProjectId.create(), Option.none()), Instant.EPOCH), Message.class);
        assertRoundTrip(new Message.IdleMessage(id, IdleOutcome.SUCCEEDED, Option.none(), Instant.EPOCH), Message.class);
    }

    @Test
    void roundTripsEveryContentVariant() {
        assertRoundTrip(new Content.TextContent("text", Instant.EPOCH), Content.class);
        assertRoundTrip(new Content.ReasoningContent("reasoning", Instant.EPOCH), Content.class);
        assertRoundTrip(new Content.ToolCardContent(new ToolCard(CallId.create(), new ToolName("read"),
                JsonValue.ofNull(), new ToolState.Running(JsonValue.ofNull(), Map.of()), false,
                Instant.EPOCH)), Content.class);
    }

    @Test
    void roundTripsEveryToolStateAndContentItem() {
        assertRoundTrip(new ToolState.Streaming("in"), ToolState.class);
        assertRoundTrip(new ToolState.Running(JsonValue.ofNull(), Map.of("k", "v")), ToolState.class);
        assertRoundTrip(new ToolState.Completed(JsonValue.ofNull(),
                List.of(new ToolContent.TextItem("ok")), Map.of()), ToolState.class);
        assertRoundTrip(new ToolState.Failed(JsonValue.ofNull(),
                new SessionError.Unknown("x", "y"), Option.none(), Map.of()), ToolState.class);

        assertRoundTrip(new ToolContent.TextItem("text"), ToolContent.class);
        assertRoundTrip(new ToolContent.FileItem("file:///a", "text/plain", Option.some("a")), ToolContent.class);
    }

    @Test
    void roundTripsEveryInboxItem() {
        assertRoundTrip(new InboxItem.UserItem("text", List.of(), List.of(), List.of(), Map.of()), InboxItem.class);
        assertRoundTrip(new InboxItem.SyntheticItem("text", Option.none(), Map.of()), InboxItem.class);
        assertRoundTrip(new InboxItem.CompactionItem(CompactionReason.OVERFLOW), InboxItem.class);
        assertRoundTrip(new InboxItem.MoveItem("/tmp"), InboxItem.class);
    }

    @Test
    void roundTripsEverySessionError() {
        assertRoundTrip(new SessionError.ProviderError("t", "m", 429, "body", Duration.ofSeconds(1)), SessionError.class);
        assertRoundTrip(new SessionError.PermissionRejected("t", "m"), SessionError.class);
        assertRoundTrip(new SessionError.ToolExecutionError("t", "m"), SessionError.class);
        assertRoundTrip(new SessionError.CompactionError("t", "m"), SessionError.class);
        assertRoundTrip(new SessionError.Unknown("t", "m"), SessionError.class);
    }

    @Test
    void roundTripsEveryFailureKind() {
        assertRoundTrip(new FailureKind.RateLimited(Option.some(Duration.ofSeconds(1))), FailureKind.class);
        assertRoundTrip(new FailureKind.ProviderInternal(Option.none()), FailureKind.class);
        assertRoundTrip(new FailureKind.Transport(
                de.augmentia.oc.engine.core.contract.provider.TransportDelivery.UNSENT,
                de.augmentia.oc.engine.core.contract.provider.TransportOperation.SEND, "code"), FailureKind.class);
        assertRoundTrip(new FailureKind.InvalidOutput(
                de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.UNKNOWN), FailureKind.class);
        assertRoundTrip(new FailureKind.InvalidRequest(
                de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification.OTHER), FailureKind.class);
        assertRoundTrip(new FailureKind.Authentication(Option.none()), FailureKind.class);
        assertRoundTrip(new FailureKind.QuotaExceeded(Option.none()), FailureKind.class);
        assertRoundTrip(new FailureKind.ContentPolicy(), FailureKind.class);
        assertRoundTrip(new FailureKind.UnsupportedOperation("op"), FailureKind.class);
        assertRoundTrip(new FailureKind.NoRoute(), FailureKind.class);
        assertRoundTrip(new FailureKind.UnknownProvider(), FailureKind.class);
        assertRoundTrip(new FailureKind.Timeout(), FailureKind.class);
    }

    @Test
    void roundTripsProviderMessagesAndContents() {
        assertRoundTrip(new ModelContent.Text("hi"), ModelContent.class);
        assertRoundTrip(new ModelContent.Media(Modality.IMAGE, new byte[]{1, 2}, Option.some("a")), ModelContent.class);
        assertRoundTrip(new ModelContent.ToolCall(CallId.create(), new ToolName("read"), JsonValue.ofNull()),
                ModelContent.class);

        assertRoundTrip(new ModelMessage.User(List.of(new ModelContent.Text("hi"))), ModelMessage.class);
        assertRoundTrip(new ModelMessage.Assistant(List.of(new ModelContent.Text("hi"))), ModelMessage.class);
        assertRoundTrip(new ModelMessage.ToolResult(CallId.create(), new ToolName("read"),
                new OutcomePayload.Text("ok")), ModelMessage.class);
    }

    @Test
    void roundTripsEveryOutcomePayload() {
        assertRoundTrip(new OutcomePayload.Text("text"), OutcomePayload.class);
        assertRoundTrip(new OutcomePayload.Json(JsonValue.of("value")), OutcomePayload.class);
        assertRoundTrip(new OutcomePayload.Content(List.of(new ToolContent.TextItem("ok")), Option.none()),
                OutcomePayload.class);
    }

    private static <T> void assertRoundTrip(T value, Class<T> type) {
        JsonValue encoded = ContractCodec.encode(value, type);
        T decoded = ContractCodec.decode(encoded, type);
        assertThat(decoded).as(type.getSimpleName() + " / " + value.getClass().getSimpleName()).isEqualTo(value);
        assertThat(ContractCodec.encode(decoded, type)).isEqualTo(encoded);
    }
}
