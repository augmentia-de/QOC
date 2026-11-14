package de.augmentia.oc.engine.runtime;

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
import de.augmentia.oc.engine.runtime.context.Prepared;
import de.augmentia.oc.engine.runtime.step.Step;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;
import de.augmentia.oc.engine.testing.InMemoryToolOutput;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral tests for parallel tool execution of a {@link Step}.
 *
 * <p>Deckt drei Invarianten ab:</p>
 * <ul>
 *   <li>Multiple tool calls in a step run concurrently (G-05).</li>
 *   <li>A {@code question} tool forces sequential execution.</li>
 *   <li>Parallelism is limited to {@code MAX_PARALLEL_TOOLS} (8).</li>
 * </ul>
 */
class ParallelToolExecutionTest {

    private static final ToolName ALPHA = new ToolName("alpha");
    private static final ToolName BETA = new ToolName("beta");
    private static final ToolName QUESTION = new ToolName("question");
    private static final ToolName ECHO = new ToolName("echo");

    @Test
    void twoToolsInOneStepRunConcurrently() {
        CountDownLatch bothInFlight = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        Map<ToolName, ToolDefinition> tools = new LinkedHashMap<>();
        tools.put(ALPHA, blockingTool(ALPHA, bothInFlight, active, maxActive));
        tools.put(BETA, blockingTool(BETA, bothInFlight, active, maxActive));

        Step step = new Step(provider(List.of(
                new ModelEvent.ToolCallRequested(CallId.create(), ALPHA, JsonValue.of(Map.of())),
                new ModelEvent.ToolCallRequested(CallId.create(), BETA, JsonValue.of(Map.of())),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))),
                runtime(tools));

        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(2);
        assertThat(result.toolCalls()).allSatisfy(call ->
                assertThat(call.outcome()).isInstanceOf(ToolOutcome.Ok.class));
        // Both tools were in-flight simultaneously. With sequential execution
        // maxActive would be 1 (the first waits in vain for the second).
        assertThat(maxActive.get()).isEqualTo(2);
    }

    @Test
    void questionToolForcesSequentialExecution() {
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        ToolDefinition question = new ToolDefinition(QUESTION, "question", Schema.obj(Map.of()),
                Option.none(), (input, context) -> {
                    order.add("question:start");
                    sleep(150);
                    order.add("question:end");
                    return new ToolOutcome.Ok(ToolResult.of("answered"));
                }, null);

        ToolDefinition echo = new ToolDefinition(ECHO, "echo", Schema.obj(Map.of()),
                Option.none(), (input, context) -> {
                    order.add("echo:start");
                    return new ToolOutcome.Ok(ToolResult.of("ok"));
                }, null);

        Map<ToolName, ToolDefinition> tools = new LinkedHashMap<>();
        tools.put(QUESTION, question);
        tools.put(ECHO, echo);

        Step step = new Step(provider(List.of(
                new ModelEvent.ToolCallRequested(CallId.create(), QUESTION, JsonValue.of(Map.of())),
                new ModelEvent.ToolCallRequested(CallId.create(), ECHO, JsonValue.of(Map.of())),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))),
                runtime(tools));

        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(2);
        // With parallel execution "echo:start" would precede "question:end".
        assertThat(order).containsExactly(
                "question:start", "question:end", "echo:start");
    }

    @Test
    void parallelToolCountIsCappedAtEight() {
        int calls = 10;
        CountDownLatch eightInFlight = new CountDownLatch(8);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        ToolDefinition echo = new ToolDefinition(ECHO, "echo", Schema.obj(Map.of()),
                Option.none(), (input, context) -> {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    eightInFlight.countDown();
                    try {
                        eightInFlight.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    sleep(20);
                    active.decrementAndGet();
                    return new ToolOutcome.Ok(ToolResult.of("ok"));
                }, null);

        List<ModelEvent> events = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            events.add(new ModelEvent.ToolCallRequested(CallId.create(), ECHO, JsonValue.of(Map.of())));
        }
        events.add(new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));

        Step step = new Step(provider(events), runtime(Map.of(ECHO, echo)));

        StepResult.Finished result = (StepResult.Finished) step.run(emptyPrepared(), context());

        assertThat(result.toolCalls()).hasSize(calls);
        assertThat(result.toolCalls()).allSatisfy(call ->
                assertThat(call.outcome()).isInstanceOf(ToolOutcome.Ok.class));
        assertThat(maxActive.get()).isEqualTo(8);
    }

    private static ToolDefinition blockingTool(ToolName name, CountDownLatch bothInFlight,
                                               AtomicInteger active, AtomicInteger maxActive) {
        return new ToolDefinition(name, name.value(), Schema.obj(Map.of()), Option.none(),
                (input, context) -> {
                    int now = active.incrementAndGet();
                    maxActive.accumulateAndGet(now, Math::max);
                    bothInFlight.countDown();
                    try {
                        if (!bothInFlight.await(2, TimeUnit.SECONDS)) {
                            return new ToolOutcome.Failed(
                                    new SessionError.ToolExecutionError(
                                            "not_parallel", "second tool did not start"));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        active.decrementAndGet();
                    }
                    return new ToolOutcome.Ok(ToolResult.of("ok"));
                }, null);
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

    private static ToolRuntime runtime(Map<ToolName, ToolDefinition> tools) {
        return new ToolRuntime(new ToolSnapshot(tools), ToolAuthorizer.defaultAuthorizer(),
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
