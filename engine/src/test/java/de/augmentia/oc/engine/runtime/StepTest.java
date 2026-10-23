package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.permission.PermissionDenied;
import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidRequestClassification;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.runtime.context.Prepared;
import de.augmentia.oc.engine.runtime.step.Step;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepTest {

    private static final ToolName ECHO = new ToolName("echo");

    @Test
    void finishedStreamIsReturned() {
        Step step = new Step(provider(List.of(
                new ModelEvent.TextStarted("b"),
                new ModelEvent.TextEnded("b", "hi"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.of(1, 2), JsonValue.ofNull()))),
                runtime(ToolAuthorizer.defaultAuthorizer()));

        StepResult result = step.run(emptyPrepared(), context());

        assertThat(result).isInstanceOf(StepResult.Finished.class);
        StepResult.Finished finished = (StepResult.Finished) result;
        assertThat(finished.reason()).isEqualTo(FinishReason.NORMAL);
        assertThat(finished.usage().total()).isEqualTo(3);
    }

    @Test
    void requestedToolIsExecuted() {
        CallId call = CallId.create();
        Step step = new Step(provider(List.of(
                new ModelEvent.ToolCallRequested(call, ECHO, JsonValue.of(Map.of())),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))),
                runtime(ToolAuthorizer.defaultAuthorizer()));

        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().getFirst().outcome()).isInstanceOf(ToolOutcome.Ok.class);
    }

    @Test
    void multipleRequestedToolsAreExecuted() {
        CallId first = CallId.create();
        CallId second = CallId.create();
        Step step = new Step(provider(List.of(
                new ModelEvent.ToolCallRequested(first, ECHO, JsonValue.of(Map.of())),
                new ModelEvent.ToolCallRequested(second, ECHO, JsonValue.of(Map.of())),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))),
                runtime(ToolAuthorizer.defaultAuthorizer()));

        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls().get(0).call().id()).isEqualTo(first);
        assertThat(result.toolCalls().get(1).call().id()).isEqualTo(second);
    }

    @Test
    void contextOverflowCompactsAtMostOncePerStep() {
        Deque<List<ModelEvent>> responses = new ArrayDeque<>();
        responses.add(List.of(new ModelEvent.ProviderFailed(
                new FailureKind.InvalidRequest(InvalidRequestClassification.CONTEXT_OVERFLOW),
                "too large", Option.none(), Option.none())));
        responses.add(List.of(new ModelEvent.ProviderFailed(
                new FailureKind.InvalidRequest(InvalidRequestClassification.CONTEXT_OVERFLOW),
                "still too large", Option.none(), Option.none())));
        Step step = new Step(request -> responses.removeFirst().stream(),
                runtime(ToolAuthorizer.defaultAuthorizer()));

        assertThat(step.run(emptyPrepared(), context())).isInstanceOf(StepResult.NeedsCompaction.class);
        assertThat(step.run(emptyPrepared(), context())).isInstanceOf(StepResult.Failed.class);
    }

    @Test
    void missingFinishIsIncompleteOutput() {
        Step step = new Step(provider(List.of(new ModelEvent.TextStarted("b"))),
                runtime(ToolAuthorizer.defaultAuthorizer()));

        StepResult result = step.run(emptyPrepared(), context());

        assertThat(result).isInstanceOf(StepResult.Failed.class);
        assertThat(((StepResult.Failed) result).kind())
                .isInstanceOf(FailureKind.InvalidOutput.class);
    }

    @Test
    void providerFailureIsReturnedNotThrown() {
        Step step = new Step(provider(List.of(new ModelEvent.ProviderFailed(
                new FailureKind.RateLimited(Option.none()), "slow", Option.none(), Option.none()))),
                runtime(ToolAuthorizer.defaultAuthorizer()));

        assertThat(step.run(emptyPrepared(), context())).isInstanceOf(StepResult.Failed.class);
    }

    @Test
    void permissionDenialPropagates() {
        CallId call = CallId.create();
        Step step = new Step(provider(List.of(
                new ModelEvent.ToolCallRequested(call, ECHO, JsonValue.of(Map.of())),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))),
                runtimeWithGate(request -> {
                    throw new PermissionDenied(request, "no");
                }));

        assertThatThrownBy(() -> step.run(emptyPrepared(), context()))
                .isInstanceOf(PermissionDenied.class);
    }

    private static ProviderAdapter provider(List<ModelEvent> events) {
        return request -> events.stream();
    }

    private static ToolRuntime runtime(ToolAuthorizer authorizer) {
        return runtimeWithGateAndAuthorizer(authorizer, request -> {});
    }

    private static ToolRuntime runtimeWithGate(de.augmentia.oc.engine.core.contract.permission.PermissionGate gate) {
        return runtimeWithGateAndAuthorizer(ToolAuthorizer.defaultAuthorizer(), gate);
    }

    private static ToolRuntime runtimeWithGateAndAuthorizer(ToolAuthorizer authorizer,
            de.augmentia.oc.engine.core.contract.permission.PermissionGate gate) {
        ToolDefinition echo = new ToolDefinition(ECHO, "echo", Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")), null);
        return new ToolRuntime(new ToolSnapshot(Map.of(ECHO, echo)), authorizer,
                gate, noopHooks(), new InMemoryToolOutput());
    }

    private static Hooks noopHooks() {
        return new Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                return call;
            }

            @Override
            public ToolResult afterToolCall(ToolContext context, ToolResult result) {
                return result;
            }
        };
    }

    private static Prepared emptyPrepared() {
        return new Prepared(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of("system"), List.of(), List.of(), Option.none(), null, Map.of(),
                Option.none(), Map.of());
    }

    private static ToolContext context() {
        return new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
    }
}
