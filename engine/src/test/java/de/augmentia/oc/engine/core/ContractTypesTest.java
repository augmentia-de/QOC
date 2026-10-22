package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.contract.permission.*;
import de.augmentia.oc.engine.core.contract.provider.*;
import de.augmentia.oc.engine.core.contract.tool.*;
import de.augmentia.oc.engine.core.error.Aborted;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.*;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractTypesTest {

    @Test
    void toolDefinitionUsesEffectiveName() {
        ToolDefinition plain = definition(new ToolName("read"), ToolOptions.defaults());
        assertThat(plain.effectiveName()).isEqualTo("read");

        ToolDefinition namespaced = definition(new ToolName("read"),
                new ToolOptions(Option.some("fs"), Option.none(), true, false));
        assertThat(namespaced.effectiveName()).isEqualTo("fs_read");

        ToolDefinition invalidCharacters = definition(new ToolName("read file"),
                new ToolOptions(Option.some("mcp server"), Option.none(), true, false));
        assertThat(invalidCharacters.effectiveName()).isEqualTo("mcp_server_read_file");
    }

    @Test
    void toolDefinitionDefaultsOutputSchemaAndOptions() {
        ToolDefinition definition = definition(new ToolName("read"), null);
        assertThat(definition.outputSchema().isEmpty()).isTrue();
        assertThat(definition.options().codemode()).isTrue();
        assertThat(definition.options().pinned()).isFalse();
    }

    @Test
    void toolResultKeepsOutputContentAndMetadataApart() {
        ToolResult result = new ToolResult(Option.some(JsonValue.of("42")),
                List.of(new ToolContent.TextItem("answer")), Map.of("ui", "hint"));
        assertThat(result.output().isPresent()).isTrue();
        assertThat(result.content()).hasSize(1);
        assertThat(result.metadata()).containsEntry("ui", "hint");

        ToolResult text = ToolResult.of("plain");
        assertThat(text.output().isEmpty()).isTrue();
        assertThat(text.content()).containsExactly(new ToolContent.TextItem("plain"));
    }

    @Test
    void toolOutcomeSeparatesOkAndFailure() {
        ToolOutcome ok = new ToolOutcome.Ok(ToolResult.of("done"));
        ToolOutcome failed = new ToolOutcome.Failed(new ToolFailure("broken"));
        assertThat(ok).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(failed).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) failed).error().message()).isEqualTo("broken");
    }

    @Test
    void permissionRequestValidatesActionAndResources() {
        assertThatThrownBy(() -> new PermissionRequest(PermissionRequestId.create(), SessionId.create(),
                " ", List.of("r"), List.of(), Map.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new PermissionRequest(PermissionRequestId.create(), SessionId.create(),
                "read", List.of(), List.of(), Map.of(), null, null))
                .isInstanceOf(IllegalArgumentException.class);

        PermissionRequest request = new PermissionRequest(PermissionRequestId.create(), SessionId.create(),
                "read", List.of("/tmp"), List.of("*"), Map.of(),
                new PermissionRequest.InvocationSource("tool", MessageId.create(), CallId.create()), null);
        assertThat(request.resources()).containsExactly("/tmp");
        assertThat(request.save()).containsExactly("*");
        assertThat(request.source().type()).isEqualTo("tool");
    }

    @Test
    void permissionDeniedCarriesTheRequest() {
        PermissionRequest request = new PermissionRequest(PermissionRequestId.create(), SessionId.create(),
                "shell", List.of("rm -rf /"), List.of(), Map.of(), null, "denied");
        PermissionDenied denied = new PermissionDenied(request);
        assertThat(denied.request()).isEqualTo(request);
        assertThat(denied).hasMessage("permission denied");
    }

    @Test
    void permissionHookMayChangeTheEffect() {
        PermissionEvaluation evaluation = new PermissionEvaluation(SessionId.create(), Option.none(),
                "read", List.of("*"), Map.of(), Effect.ASK);
        PermissionHook hook = e -> new Result.Ok<>(new PermissionEvaluation(e.sessionId(), e.agent(),
                e.action(), e.resources(), e.metadata(), Effect.DENY));
        Result<PermissionEvaluation> result = hook.evaluate(evaluation);
        assertThat(result).isInstanceOf(Result.Ok.class);
        assertThat(result.valueOrNull().effect()).isEqualTo(Effect.DENY);
    }

    @Test
    void permissionGateUsesTheDocumentedChannels() {
        PermissionRequest request = new PermissionRequest(PermissionRequestId.create(), SessionId.create(),
                "read", List.of("*"), List.of(), Map.of(), null, null);
        PermissionGate denying = r -> {
            throw new PermissionDenied(r, "no");
        };
        assertThatThrownBy(() -> denying.assertAllowed(request)).isInstanceOf(PermissionDenied.class);

        PermissionGate aborting = r -> {
            throw new Aborted("stopped");
        };
        assertThatThrownBy(() -> aborting.assertAllowed(request)).isInstanceOf(Aborted.class);
    }

    @Test
    void modelRequestCarriesNeutralAndProviderSpecificParts() {
        ModelRequest request = new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of("system"), List.of(new ModelMessage.User(List.of(new ModelContent.Text("hi")))),
                List.of(), Option.none(), GenerationOptions.defaults(),
                Map.of("vendor_flag", "on"), Map.of("x-session", "s1"), Option.some("cache"));
        assertThat(request.system()).containsExactly("system");
        assertThat(request.providerOptions()).containsEntry("vendor_flag", "on");
        assertThat(request.headers()).containsEntry("x-session", "s1");
        assertThat(request.cacheKey().orElse("")).isEqualTo("cache");
        assertThat(request.toolChoice().isEmpty()).isTrue();
    }

    @Test
    void modelEventsAreClosedVariants() {
        assertThat(new ModelEvent.TextDelta("b1", "hi")).isInstanceOf(ModelEvent.class);
        assertThat(new ModelEvent.ToolInputStarted(CallId.create(), new ToolName("read")))
                .isInstanceOf(ModelEvent.class);
        assertThat(new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.of(1, 2), JsonValue.ofNull()))
                .isInstanceOf(ModelEvent.class);
        assertThat(new ModelEvent.ProviderFailed(new FailureKind.Timeout(), "timeout",
                Option.none(), Option.none())).isInstanceOf(ModelEvent.class);
    }

    @Test
    void failureKindCarriesTheClassNotTheMessage() {
        assertThat(new FailureKind.RateLimited(Option.of(Duration.ofSeconds(2))))
                .isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.Transport(TransportDelivery.ACCEPTED, TransportOperation.SEND, "500"))
                .isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.InvalidOutput(InvalidOutputClassification.INCOMPLETE_STREAM))
                .isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.InvalidRequest(InvalidRequestClassification.CONTEXT_OVERFLOW))
                .isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.Authentication(Option.none())).isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.QuotaExceeded(Option.none())).isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.ContentPolicy()).isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.UnsupportedOperation("stream")).isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.NoRoute()).isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.UnknownProvider()).isInstanceOf(FailureKind.class);
        assertThat(new FailureKind.ProviderInternal(Option.none())).isInstanceOf(FailureKind.class);
    }

    @Test
    void providerFailureKeepsStatusAndRetryAfter() {
        ModelEvent.ProviderFailed failure = new ModelEvent.ProviderFailed(
                new FailureKind.RateLimited(Option.of(Duration.ofSeconds(1))),
                "slow down", Option.of(429), Option.of(Duration.ofSeconds(1)));
        assertThat(failure.kind()).isInstanceOf(ProviderFailure.class);
        assertThat(failure.status().orElse(0)).isEqualTo(429);
        assertThat(failure.retryAfter().isPresent()).isTrue();
    }

    @Test
    void modelInfoValidatesLimitsAndCost() {
        ModelInfo info = new ModelInfo(ModelId.create(), ProviderId.create(), List.of("variant"),
                new ModelInfo.ModelLimits(1000, 500),
                new ModelInfo.CostModel(Money.zero(), Money.zero(), Money.zero(), Money.zero()),
                new ModelInfo.ModelCapabilities(List.of(Modality.TEXT), List.of(Modality.TEXT)),
                Option.none(), Option.none());
        assertThat(info.limits().context()).isEqualTo(1000);

        assertThatThrownBy(() -> new ModelInfo(ModelId.create(), ProviderId.create(), List.of(),
                new ModelInfo.ModelLimits(0, 500),
                new ModelInfo.CostModel(Money.zero(), Money.zero(), Money.zero(), Money.zero()),
                new ModelInfo.ModelCapabilities(List.of(), List.of()),
                Option.none(), Option.none()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageVariantsCarryTheirData() {
        MessageId id = MessageId.create();
        Message.AssistantMessage message = new Message.AssistantMessage(id, AgentId.create(),
                ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(new Content.TextContent("hello", null)),
                Option.some(FinishReason.NORMAL), Option.none(), Option.none(), Option.none(),
                Money.of(1), TokenCount.of(2, 3), AssistantTime.started(java.time.Instant.EPOCH));
        assertThat(message.id()).isEqualTo(id);
        assertThat(message.finish().orElse(FinishReason.UNKNOWN)).isEqualTo(FinishReason.NORMAL);
        assertThat(message.tokens().total()).isEqualTo(5);

        Message.UserMessage user = new Message.UserMessage(id, "text", List.of(), List.of(), List.of(), Map.of(), null);
        assertThat(user.text()).isEqualTo("text");
        assertThat(user.time()).isEqualTo(java.time.Instant.EPOCH);
    }

    @Test
    void toolStateFollowsTheToolLifecycle() {
        ToolState streaming = new ToolState.Streaming("{\"path\"");
        ToolState running = new ToolState.Running(JsonValue.ofNull(), Map.of());
        ToolState completed = new ToolState.Completed(JsonValue.ofNull(),
                List.of(new ToolContent.TextItem("ok")), Map.of());
        ToolState failed = new ToolState.Failed(JsonValue.ofNull(),
                new SessionError.Unknown("x", "y"), Option.none(), Map.of());
        assertThat(streaming).isInstanceOf(ToolState.class);
        assertThat(running).isInstanceOf(ToolState.class);
        assertThat(completed).isInstanceOf(ToolState.class);
        assertThat(failed).isInstanceOf(ToolState.class);
    }

    @Test
    void permissionEventsAreEphemeralVariants() {
        PermissionRequest request = new PermissionRequest(PermissionRequestId.create(), SessionId.create(),
                "read", List.of("*"), List.of(), Map.of(), null, null);
        PermissionEvent asked = new PermissionEvent.Asked(request);
        PermissionEvent replied = new PermissionEvent.Replied(request.sessionId(), request.id(), Reply.ONCE);
        assertThat(asked).isInstanceOf(PermissionEvent.class);
        assertThat(replied).isInstanceOf(PermissionEvent.class);
    }

    @Test
    void approvalRequestAndResult() {
        ApprovalRequest request = new ApprovalRequest("shell", "Run git status?", List.of("shell"));
        assertThat(request.reviewActions()).containsExactly("shell");

        ApprovalResult approved = new ApprovalResult.Approved();
        ApprovalResult denied = new ApprovalResult.Denied(Option.some("too risky"));
        assertThat(approved).isInstanceOf(ApprovalResult.class);
        assertThat(((ApprovalResult.Denied) denied).feedback().orElse("")).isEqualTo("too risky");
        assertThat(new ApprovalResult.Denied(null).feedback().isEmpty()).isTrue();
    }

    @Test
    void replyAndEffectAreClosedEnums() {
        assertThat(Reply.values()).containsExactly(Reply.ONCE, Reply.ALWAYS, Reply.REJECT);
        assertThat(Effect.values()).containsExactly(Effect.ALLOW, Effect.DENY, Effect.ASK);
    }

    private static ToolDefinition definition(ToolName name, ToolOptions options) {
        return new ToolDefinition(name, "description", Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")),
                options);
    }
}
