package de.augmentia.oc.engine.runtime.tool.subagent;

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
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.runtime.context.Prepared;
import de.augmentia.oc.engine.runtime.step.Step;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verhaltenstests für parallele Subagent-Aufrufe über den {@link Step}.
 *
 * <p>Zeigt, dass zwei {@code subagent}-Tool-Calls in einem Modell-Schritt
 * gleichzeitig laufen und jeweils eine eigene Child-Session erhalten. Das ist
 * die Grundlage für „zwei Research-Subagents gleichzeitig“.</p>
 */
class SubagentParallelTest {

    @Test
    void twoSubagentCallsRunConcurrentlyInDifferentSessions() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch bothInFlight = new CountDownLatch(2);
        Set<SessionId> childSessions = ConcurrentHashMap.newKeySet();

        SubagentRunner runner = (parent, agent, prompt, depth, principal) -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            bothInFlight.countDown();
            try {
                if (!bothInFlight.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("second subagent did not start");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            sleep(50);
            active.decrementAndGet();
            SessionId child = SessionId.create();
            childSessions.add(child);
            return new SubagentRunner.SubagentResult(agent + ":" + prompt,
                    TokenCount.zero(), child);
        };

        SubagentTool tool = new SubagentTool(runner, request -> { });
        ToolDefinition subagent = SubagentTool.definition(tool);
        ToolSnapshot snapshot = new ToolSnapshot(Map.of(subagent.name(), subagent));

        List<ModelEvent> events = List.of(
                new ModelEvent.ToolCallRequested(CallId.create(), subagent.name(),
                        JsonValue.of(Map.of(
                                "agent", JsonValue.of("researcher-a"),
                                "prompt", JsonValue.of("task-a")))),
                new ModelEvent.ToolCallRequested(CallId.create(), subagent.name(),
                        JsonValue.of(Map.of(
                                "agent", JsonValue.of("researcher-b"),
                                "prompt", JsonValue.of("task-b")))),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));

        Step step = new Step(provider(events), runtime(snapshot));
        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls()).allSatisfy(call ->
                assertThat(call.outcome()).isInstanceOf(ToolOutcome.Ok.class));
        // Beide Subagents waren gleichzeitig in-flight.
        assertThat(maxActive.get()).isEqualTo(2);
        // Jeder Subagent hat eine eigene Child-Session erhalten.
        assertThat(childSessions).hasSize(2);
        assertThat(toolText(result, 0)).contains("researcher-a:task-a");
        assertThat(toolText(result, 1)).contains("researcher-b:task-b");
        tool.close();
    }

    @Test
    void batchTasksRunSequentiallyEvenWhenAStepWouldAllowParallelism() {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        SubagentRunner runner = (parent, agent, prompt, depth, principal) -> {
            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            sleep(30);
            active.decrementAndGet();
            return new SubagentRunner.SubagentResult(agent + ":" + prompt, TokenCount.zero());
        };

        SubagentTool tool = new SubagentTool(runner, request -> { });
        ToolDefinition subagent = SubagentTool.definition(tool);
        ToolSnapshot snapshot = new ToolSnapshot(Map.of(subagent.name(), subagent));

        List<ModelEvent> events = List.of(
                new ModelEvent.ToolCallRequested(CallId.create(), subagent.name(),
                        JsonValue.of(Map.of("tasks", JsonValue.of(List.of(
                                JsonValue.of(Map.of(
                                        "agent", JsonValue.of("a"),
                                        "prompt", JsonValue.of("one"))),
                                JsonValue.of(Map.of(
                                        "agent", JsonValue.of("b"),
                                        "prompt", JsonValue.of("two")))))))),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));

        Step step = new Step(provider(events), runtime(snapshot));
        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(toolText(result, 0)).contains("a:one").contains("b:two");
        // Das tasks-Array wird bewusst sequentiell abgearbeitet.
        assertThat(maxActive.get()).isEqualTo(1);
        tool.close();
    }

    private static String toolText(StepResult.Finished result, int index) {
        ToolOutcome outcome = result.toolCalls().get(index).outcome();
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        ToolResult toolResult = ((ToolOutcome.Ok) outcome).result();
        StringBuilder text = new StringBuilder();
        toolResult.content().forEach(content -> text.append(content.toString()));
        return text.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ProviderAdapter provider(List<ModelEvent> events) {
        return request -> events.stream();
    }

    private static ToolRuntime runtime(ToolSnapshot snapshot) {
        return new ToolRuntime(snapshot, ToolAuthorizer.defaultAuthorizer(),
                request -> { }, noopHooks(), new InMemoryToolOutput());
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
