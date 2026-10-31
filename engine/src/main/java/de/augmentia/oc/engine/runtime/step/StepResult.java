package de.augmentia.oc.engine.runtime.step;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.TokenCount;

import java.util.List;

/** Result of one model step. */
public sealed interface StepResult {

    record Finished(FinishReason reason, TokenCount usage, List<ModelEvent> events,
                    List<ToolCallOutcome> toolCalls) implements StepResult {
        public Finished {
            if (reason == null) reason = FinishReason.UNKNOWN;
            if (usage == null) usage = TokenCount.zero();
            events = events == null ? List.of() : List.copyOf(events);
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    record Failed(FailureKind kind, String message) implements StepResult {
        public Failed {
            if (kind == null) kind = new FailureKind.InvalidOutput(
                    de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.UNKNOWN);
            if (message == null) message = "";
        }
    }

    /** Context overflow and the per-step compaction budget is still open. */
    record NeedsCompaction() implements StepResult {}

    /** A tool call is paused pending human approval; the session is suspended. */
    record Paused(String approvalId, String approvalUrl) implements StepResult {
        public Paused {
            if (approvalId == null || approvalId.isBlank()) throw new IllegalArgumentException("approvalId must not be blank");
            if (approvalUrl == null) approvalUrl = "";
        }
    }

    record ToolCallOutcome(ToolCall call, ToolOutcome outcome) {
        public ToolCallOutcome {
            if (call == null || outcome == null) {
                throw new IllegalArgumentException("tool call outcome fields must not be null");
            }
        }
    }
}
