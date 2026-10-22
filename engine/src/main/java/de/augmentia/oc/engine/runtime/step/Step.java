package de.augmentia.oc.engine.runtime.step;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.eval.OverflowCompaction;
import de.augmentia.oc.engine.core.eval.ProviderClassifier;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.runtime.context.Prepared;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One model step: consume the stream completely, publish live events, execute
 * requested tools and return one terminal result.
 */
public final class Step {

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
        List<ModelEvent> events = provider.stream(prepared.toRequest()).toList();
        List<StepResult.ToolCallOutcome> outcomes = new ArrayList<>();
        FinishReason finish = null;
        TokenCount usage = TokenCount.zero();

        for (ModelEvent event : events) {
            publish(event, sink);
            switch (event) {
                case ModelEvent.ToolCallRequested call -> {
                    ToolOutcome outcome = tools.execute(
                            new ToolCall(call.callId(), call.name(), call.input()), context);
                    outcomes.add(new StepResult.ToolCallOutcome(
                            new ToolCall(call.callId(), call.name(), call.input()), outcome));
                }
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
        return new StepResult.Finished(finish, usage, events, outcomes);
    }

    private void publish(ModelEvent event, Consumer<ModelEvent> sink) {
        if (sink != null) {
            sink.accept(event);
        }
    }
}
