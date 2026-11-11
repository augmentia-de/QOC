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
    private static final ExecutorService PARALLEL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

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
        return run(prepared, context, null, 0, null);
    }

    public StepResult run(Prepared prepared, ToolContext context, Consumer<ModelEvent> liveSink) {
        return run(prepared, context, liveSink, 0, null);
    }

    public StepResult run(Prepared prepared, ToolContext context, Consumer<ModelEvent> liveSink, int callIndex) {
        return run(prepared, context, liveSink, callIndex, null);
    }

    public StepResult run(Prepared prepared, ToolContext context, Consumer<ModelEvent> liveSink, int callIndex,
                          java.util.concurrent.atomic.AtomicReference<String> summary) {
        if (prepared == null || context == null) {
            throw new IllegalArgumentException("prepared and context must not be null");
        }

        Consumer<ModelEvent> sink = liveSink == null ? this.liveSink : liveSink;
        LOG.fine("step run start");
        List<ModelEvent> events = provider.stream(prepared.toRequest(), sink, callIndex, summary).toList();
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

        // Execute tool calls. The `question` tool blocks until the user answers
        // (or times out); running it in parallel with other tools would let those
        // finish while the question is still open, which is confusing. When a
        // question is present we therefore run every call sequentially.
        List<StepResult.ToolCallOutcome> outcomes = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) outcomes.add(null);

        boolean hasQuestion = toolCalls.stream()
                .anyMatch(c -> "question".equalsIgnoreCase(c.name().value()));

        if (!toolCalls.isEmpty() && !hasQuestion) {
            java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(MAX_PARALLEL_TOOLS);
            try {
                List<Callable<StepResult.ToolCallOutcome>> tasks = new ArrayList<>();
                for (ModelEvent.ToolCallRequested call : toolCalls) {
                    tasks.add(() -> {
                        slots.acquire();
                        try {
                            LOG.fine("tool call requested: " + call.name());
                            ToolContext callContext = new ToolContext(context.sessionId(),
                                    context.agentId(), context.messageId(), call.callId(),
                                    null, context.principal());
                            ToolOutcome outcome = tools.execute(
                                    new ToolCall(call.callId(), call.name(), call.input()), callContext);
                            return new StepResult.ToolCallOutcome(
                                    new ToolCall(call.callId(), call.name(), call.input()), outcome);
                        } finally {
                            slots.release();
                        }
                    });
                }
                List<Future<StepResult.ToolCallOutcome>> futures = PARALLEL_EXECUTOR.invokeAll(tasks);
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
                        if (cause instanceof de.augmentia.oc.engine.core.error.PausedException pe) {
                            throw pe;
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
            } catch (de.augmentia.oc.engine.core.error.PausedException pe) {
                return new StepResult.Paused(pe.approvalId(), pe.approvalUrl());
            }
        } else if (!toolCalls.isEmpty()) {
            // Sequential execution when a question tool is present.
            for (int i = 0; i < toolCalls.size(); i++) {
                ModelEvent.ToolCallRequested call = toolCalls.get(i);
                try {
                    LOG.fine("tool call requested: " + call.name());
                    ToolContext callContext = new ToolContext(context.sessionId(),
                            context.agentId(), context.messageId(), call.callId(),
                            null, context.principal());
                    ToolOutcome outcome = tools.execute(
                            new ToolCall(call.callId(), call.name(), call.input()), callContext);
                    outcomes.set(i, new StepResult.ToolCallOutcome(
                            new ToolCall(call.callId(), call.name(), call.input()), outcome));
                } catch (de.augmentia.oc.engine.core.contract.permission.PermissionDenied pd) {
                    throw pd;
                } catch (de.augmentia.oc.engine.core.error.Aborted ab) {
                    throw ab;
                } catch (de.augmentia.oc.engine.core.error.PausedException pe) {
                    return new StepResult.Paused(pe.approvalId(), pe.approvalUrl());
                } catch (RuntimeException re) {
                    outcomes.set(i, new StepResult.ToolCallOutcome(
                            new ToolCall(call.callId(), call.name(), call.input()),
                            new ToolOutcome.Failed(new de.augmentia.oc.engine.core.model.SessionError.ToolExecutionError(
                                    "tool_failure", re.getMessage()))));
                }
            }
        }

        // Paused (async approval): stop the turn and surface the pending approval.
        for (StepResult.ToolCallOutcome outcome : outcomes) {
            if (outcome != null && outcome.outcome() instanceof ToolOutcome.Paused paused) {
                return new StepResult.Paused(paused.approvalId(), paused.approvalUrl());
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