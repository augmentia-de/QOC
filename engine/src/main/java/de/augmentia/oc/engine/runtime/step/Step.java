package de.augmentia.oc.engine.runtime.step;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.eval.OverflowCompaction;
import de.augmentia.oc.engine.core.eval.ProviderClassifier;
import de.augmentia.oc.engine.core.eval.ToolCallLeak;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.runtime.context.Prepared;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * One model step: consume the stream completely, publish live events, execute
 * requested tools in parallel and return one terminal result. Tool calls run
 * concurrently (G-05); their outcomes are kept in call order.
 */
public final class Step {
    private static final Logger LOG = Logger.getLogger(Step.class.getName());
    private static final int MAX_PARALLEL_TOOLS = 8;

    private final ProviderAdapter provider;
    private final ToolRuntime tools;
    private final OverflowCompaction overflow = new OverflowCompaction();
    private final Consumer<ModelEvent> liveSink;

    public Step(ProviderAdapter provider, ToolRuntime tools) {
        this(provider, tools, null);
    }

    public Step(ProviderAdapter provider, ToolRuntime tools, Consumer<ModelEvent> liveSink) {
        if (provider == null || tools == null) {
            throw new IllegalArgumentException("provider and tools must not be null");
        }
        this.provider = provider;
        this.tools = tools;
        this.liveSink = liveSink;
    }

    public StepResult run(Prepared prepared, ToolContext context) {
        return run(prepared, context, null);
    }

    public StepResult run(Prepared prepared, ToolContext context, Consumer<ModelEvent> liveSink) {
        if (prepared == null || context == null) {
            throw new IllegalArgumentException("prepared and context must not be null");
        }

        Consumer<ModelEvent> sink = liveSink == null ? this.liveSink : liveSink;
        LOG.fine("step run start");
        List<ModelEvent> events = provider.stream(prepared.toRequest()).toList();
        LOG.fine("provider stream events=" + events.size());

        // Collect tool calls in stream order; publish non-tool events live.
        List<ModelEvent.ToolCallRequested> toolCalls = new ArrayList<>();
        FinishReason finish = null;
        TokenCount usage = TokenCount.zero();

        for (ModelEvent event : events) {
            publish(event, sink);
            switch (event) {
                case ModelEvent.ToolCallRequested call -> toolCalls.add(call);
                case ModelEvent.Finished finished -> {
                    finish = finished.reason();
                    usage = finished.usage();
                }
                case ModelEvent.ProviderFailed failed -> {
                    if (ProviderClassifier.needsCompaction(failed.kind()) && overflow.consume()) {
                        return new StepResult.NeedsCompaction();
                    }
                    return new StepResult.Failed(failed.kind(), failed.message());
                }
                default -> {
                    // Text, reasoning and tool-input events are streamed to the projections.
                }
            }
        }

        if (finish == null) {
            return new StepResult.Failed(new FailureKind.InvalidOutput(
                    de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.INCOMPLETE_STREAM),
                    "stream ended without a finish event");
        }

        // Some providers leak native tool-call markup into the text channel when
        // tools are disabled. It is not a final answer, even with a normal finish.
        if (toolCalls.isEmpty() && toolsDisabled(prepared) && ToolCallLeak.contains(text(events))) {
            return new StepResult.Failed(new FailureKind.InvalidOutput(
                    de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.UNKNOWN),
                    "provider returned tool-call markup as text");
        }

        // Tool locking is an engine invariant, not only a provider hint. Never
        // execute a call when the prepared step explicitly disabled tools.
        if (!toolCalls.isEmpty() && toolsDisabled(prepared)) {
            List<StepResult.ToolCallOutcome> blocked = new ArrayList<>(toolCalls.size());
            for (ModelEvent.ToolCallRequested call : toolCalls) {
                blocked.add(new StepResult.ToolCallOutcome(
                        new ToolCall(call.callId(), call.name(), call.input()),
                        new ToolOutcome.Failed(new SessionError.ToolExecutionError(
                                "tools_disabled", "Tools are disabled for this step"))));
            }
            return new StepResult.Finished(finish, usage, events, blocked);
        }

        // Execute tool calls in parallel (G-05), keep outcomes in call order.
        List<StepResult.ToolCallOutcome> outcomes = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) outcomes.add(null);

        if (!toolCalls.isEmpty()) {
            ExecutorService executor = Executors.newFixedThreadPool(
                    Math.min(MAX_PARALLEL_TOOLS, toolCalls.size()));
            try {
                List<Callable<StepResult.ToolCallOutcome>> tasks = new ArrayList<>();
                for (ModelEvent.ToolCallRequested call : toolCalls) {
                    tasks.add(() -> {
                        LOG.fine("tool call requested: " + call.name());
                        ToolContext callContext = new ToolContext(context.sessionId(),
                                context.agentId(), context.messageId(), call.callId());
                        ToolOutcome outcome = tools.execute(
                                new ToolCall(call.callId(), call.name(), call.input()), callContext);
                        return new StepResult.ToolCallOutcome(
                                new ToolCall(call.callId(), call.name(), call.input()), outcome);
                    });
                }
                List<Future<StepResult.ToolCallOutcome>> futures = executor.invokeAll(tasks);
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        outcomes.set(i, futures.get(i).get());
                    } catch (java.util.concurrent.ExecutionException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof de.augmentia.oc.engine.core.contract.permission.PermissionDenied pd) {
                            throw pd;
                        }
                        if (cause instanceof de.augmentia.oc.engine.core.error.Aborted ab) {
                            throw ab;
                        }
                        ModelEvent.ToolCallRequested call = toolCalls.get(i);
                        outcomes.set(i, new StepResult.ToolCallOutcome(
                                new ToolCall(call.callId(), call.name(), call.input()),
                                new ToolOutcome.Failed(new de.augmentia.oc.engine.core.model.SessionError.ToolExecutionError(
                                        "tool_failure", cause == null ? e.getMessage() : cause.getMessage()))));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new StepResult.Failed(new FailureKind.InvalidOutput(
                        de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.INCOMPLETE_STREAM),
                        "tool execution interrupted");
            } finally {
                executor.shutdown();
            }
        }

        LOG.fine("step run finish tools=" + outcomes.size());
        return new StepResult.Finished(finish, usage, events, outcomes);
    }

    private static boolean toolsDisabled(Prepared prepared) {
        return prepared.toolChoice().orElse(null) instanceof Choice.None;
    }

    private static String text(List<ModelEvent> events) {
        StringBuilder text = new StringBuilder();
        for (ModelEvent event : events) {
            if (event instanceof ModelEvent.TextEnded ended) {
                text.append(ended.text());
            }
        }
        if (text.isEmpty()) {
            for (ModelEvent event : events) {
                if (event instanceof ModelEvent.TextDelta delta) {
                    text.append(delta.text());
                }
            }
        }
        return text.toString();
    }

    private void publish(ModelEvent event, Consumer<ModelEvent> sink) {
        if (sink != null) {
            sink.accept(event);
        }
    }
}