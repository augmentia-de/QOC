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

/**
 * One model step: build nothing here (the {@link Prepared} request is frozen),
 * consume the event stream completely, execute requested tools through the
 * tool runtime and return one terminal result.
 */
public final class Step {

    private final ProviderAdapter provider;
    private final ToolRuntime tools;
    private final OverflowCompaction overflow = new OverflowCompaction();

    public Step(ProviderAdapter provider, ToolRuntime tools) {
        if (provider == null || tools == null) {
            throw new IllegalArgumentException("provider and tools must not be null");
        }
        this.provider = provider;
        this.tools = tools;
    }

    public StepResult run(Prepared prepared, ToolContext context) {
        if (prepared == null || context == null) {
            throw new IllegalArgumentException("prepared and context must not be null");
        }

        List<ModelEvent> events = provider.stream(prepared.toRequest()).toList();
        List<StepResult.ToolCallOutcome> outcomes = new ArrayList<>();
        FinishReason finish = null;
        TokenCount usage = TokenCount.zero();

        for (ModelEvent event : events) {
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
}
