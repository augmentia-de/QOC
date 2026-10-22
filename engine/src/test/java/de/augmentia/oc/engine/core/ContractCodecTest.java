package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.event.ContractCodec;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.*;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractCodecTest {

    @Test
    void roundTripsAssistantMessage() {
        Message.AssistantMessage message = new Message.AssistantMessage(MessageId.create(), AgentId.create(),
                ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(new Content.TextContent("hello", Instant.EPOCH),
                        new Content.ToolCardContent(new ToolCard(CallId.create(), new ToolName("read"),
                                JsonValue.ofNull(), new ToolState.Completed(JsonValue.ofNull(),
                                List.of(new ToolContent.TextItem("ok")), Map.of()), true, Instant.EPOCH))),
                Option.some(FinishReason.NORMAL),
                Option.some(new SessionError.Unknown("x", "y")),
                Option.none(), Option.none(), Money.of("1.5"), TokenCount.of(2, 3),
                AssistantTime.started(Instant.EPOCH));

        JsonValue encoded = ContractCodec.encode(message);
        Message.AssistantMessage decoded = ContractCodec.decode(encoded, Message.AssistantMessage.class);

        assertThat(decoded).isEqualTo(message);
        assertThat(decoded.finish().orElse(FinishReason.UNKNOWN)).isEqualTo(FinishReason.NORMAL);
        assertThat(decoded.error().isPresent()).isTrue();
    }

    @Test
    void roundTripsModelRequest() {
        ModelRequest request = new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of("be brief"), List.of(new ModelMessage.User(List.of(new ModelContent.Text("hi"))),
                new ModelMessage.Assistant(List.of(new ModelContent.Text("hello")))),
                List.of(), Option.none(), GenerationOptions.defaults(),
                Map.of("vendor", "on"), Map.of("x-session", "s"), Option.some("key"));

        ModelRequest decoded = ContractCodec.decode(ContractCodec.encode(request), ModelRequest.class);

        assertThat(decoded).isEqualTo(request);
        assertThat(decoded.messages()).hasSize(2);
    }

    @Test
    void roundTripsPermissionRequest() {
        PermissionRequest request = new PermissionRequest(PermissionRequestId.create(), SessionId.create(),
                "read", List.of("/tmp", "/var"), List.of("*"), Map.of("ui", "hint"),
                new PermissionRequest.InvocationSource("tool", MessageId.create(), CallId.create()), "please");

        PermissionRequest decoded = ContractCodec.decode(ContractCodec.encode(request), PermissionRequest.class);

        assertThat(decoded).isEqualTo(request);
        assertThat(decoded.source().type()).isEqualTo("tool");
    }

    @Test
    void roundTripsToolResult() {
        ToolResult result = new ToolResult(Option.some(JsonValue.of("42")),
                List.of(new ToolContent.TextItem("answer"),
                        new ToolContent.FileItem("file:///a", "text/plain", Option.some("a"))),
                Map.of("ui", "hint"));

        ToolResult decoded = ContractCodec.decode(ContractCodec.encode(result), ToolResult.class);

        assertThat(decoded).isEqualTo(result);
    }

    @Test
    void roundTripsModelEvent() {
        ModelEvent event = new ModelEvent.ToolCallRequested(CallId.create(), new ToolName("read"),
                JsonValue.ofNull());

        ModelEvent decoded = ContractCodec.decode(ContractCodec.encode(event, ModelEvent.class), ModelEvent.class);

        assertThat(decoded).isEqualTo(event);
    }

    @Test
    void unknownSubtypeFails() {
        JsonValue encoded = JsonValue.of(Map.of("_type", JsonValue.of("NoSuchVariant")));
        assertThatThrownBy(() -> ContractCodec.decode(encoded, ModelEvent.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void opaqueValueTypesUseTheirStringForm() {
        SessionId id = SessionId.create();
        JsonValue encoded = ContractCodec.encode(id);
        assertThat(encoded).isEqualTo(JsonValue.of(id.value()));
        assertThat(ContractCodec.decode(encoded, SessionId.class)).isEqualTo(id);
    }
}
