package de.augmentia.oc.engine.runtime.step;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.error.Aborted;
import de.augmentia.oc.engine.core.error.PausedException;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.context.Prepared;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers the error and sink branches of the step runner. */
class StepCoverageTest {

    private static final ToolName ECHO = new ToolName("echo");
    private static final ToolName QUESTION = new ToolName("question");

    @Test
    void constructorRejectsNullDependencies() {
        assertThatThrownBy(() -> new Step(null, runtime(request -> {})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Step(provider(List.of()), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPreparedOrContextIsRejected() {
        Step step = new Step(provider(finished()), runtime(request -> {}));
        assertThatThrownBy(() -> step.run(null, context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> step.run(emptyPrepared(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sinkOverloadsPublishAllEvents() {
        Step step = new Step(provider(finished()), runtime(request -> {}));
        List<ModelEvent> sink = new ArrayList<>();

        assertThat(step.run(emptyPrepared(), context(), sink::add)).isInstanceOf(StepResult.Finished.class);
        assertThat(step.run(emptyPrepared(), context(), sink::add, 1)).isInstanceOf(StepResult.Finished.class);
        assertThat(step.run(emptyPrepared(), context(), sink::add, 2, null))
                .isInstanceOf(StepResult.Finished.class);

        assertThat(sink).hasSize(6);
        assertThat(sink.getFirst()).isInstanceOf(ModelEvent.TextEnded.class);
    }

    @Test
    void abortDuringToolExecutionIsNotConverted() {
        Step step = new Step(provider(toolAndFinish(ECHO)),
                runtime(request -> {
                    throw new Aborted("stop");
                }));

        assertThatThrownBy(() -> step.run(emptyPrepared(), context()))
                .isInstanceOf(Aborted.class);
    }

    @Test
    void unexpectedToolExceptionBecomesAFailedOutcome() {
        Step step = new Step(provider(toolAndFinish(ECHO)),
                runtime(request -> {
                    throw new IllegalStateException("boom");
                }));

        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(1);
        ToolOutcome.Failed failed = (ToolOutcome.Failed) result.toolCalls().getFirst().outcome();
        assertThat(failed.error().message()).isEqualTo("boom");
    }

    @Test
    void pausedToolOutcomeSuspendsTheStep() {
        Step step = new Step(provider(toolAndFinish(ECHO)),
                runtime(request -> {
                    throw new PausedException("ap-9", "https://example.test/wait");
                }));

        StepResult.Paused paused = (StepResult.Paused) step.run(emptyPrepared(), context());

        assertThat(paused.approvalId()).isEqualTo("ap-9");
        assertThat(paused.approvalUrl()).isEqualTo("https://example.test/wait");
    }

    @Test
    void questionForcesSequentialExecutionAndMapsRuntimeErrors() {
        Step step = new Step(provider(toolAndFinish(QUESTION)),
                runtime(request -> {
                    throw new IllegalStateException("question broke");
                }));

        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(1);
        ToolOutcome.Failed failed = (ToolOutcome.Failed) result.toolCalls().getFirst().outcome();
        assertThat(failed.error().message()).isEqualTo("question broke");
    }

    @Test
    void leakedToolMarkupIsDetectedInTextDeltas() {
        Step step = new Step(provider(events(
                new ModelEvent.TextDelta("b", "<|DSML| calls>\n<|DSML| invoke name=\"read\">"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))),
                runtime(request -> {}));

        StepResult result = step.run(preparedWithToolsDisabled(), context());

        assertThat(result).isInstanceOf(StepResult.Failed.class);
        assertThat(((StepResult.Failed) result).kind()).isInstanceOf(FailureKind.InvalidOutput.class);
    }

    private static List<ModelEvent> finished() {
        return events(new ModelEvent.TextEnded("b", "done"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));
    }

    private static List<ModelEvent> toolAndFinish(ToolName name) {
        return events(toolCall(name),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));
    }

    private static List<ModelEvent> events(ModelEvent... events) {
        return List.of(events);
    }

    private static ModelEvent.ToolCallRequested toolCall(ToolName name) {
        return new ModelEvent.ToolCallRequested(CallId.create(), name, JsonValue.of(Map.of()));
    }

    private static ProviderAdapter provider(List<ModelEvent> events) {
        return request -> events.stream();
    }

    private static ToolRuntime runtime(PermissionGate gate) {
        ToolDefinition echo = new ToolDefinition(ECHO, "echo", Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")), null);
        ToolDefinition question = new ToolDefinition(QUESTION, "question", Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("asked")), null);
        return new ToolRuntime(new ToolSnapshot(Map.of(ECHO, echo, QUESTION, question)),
                ToolAuthorizer.defaultAuthorizer(), gate, noopHooks(), new InMemoryToolOutput());
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
        return prepared(Option.none());
    }

    private static Prepared preparedWithToolsDisabled() {
        return prepared(Option.some(new Choice.None()));
    }

    private static Prepared prepared(Option<Choice> choice) {
        return new Prepared(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of("system"), List.of(), List.of(), choice, null, Map.of(),
                Option.none(), Map.of());
    }

    private static ToolContext context() {
        return new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
    }
}
