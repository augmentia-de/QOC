package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.model.DecisionRequest;
import de.augmentia.oc.engine.core.model.DecisionResult;
import de.augmentia.oc.engine.core.port.runtime.DecisionModel;

import java.util.List;

/**
 * LoopDetector that consults an optional DecisionModel to confirm a heuristic
 * loop detection. Fail-open: on unavailability or error the heuristic result
 * stands.
 */
public final class DecisionLoopDetector implements LoopDetector {

    private final LoopDetector delegate;
    private final DecisionModel decisionModel;

    public DecisionLoopDetector(LoopDetector delegate, DecisionModel decisionModel) {
        this.delegate = delegate == null ? new LoopDetector.Default() : delegate;
        this.decisionModel = decisionModel;
    }

    @Override
    public boolean isLoop(List<ToolCall> recentCalls, ToolCall newCall, int threshold) {
        boolean heuristic = delegate.isLoop(recentCalls, newCall, threshold);
        if (!heuristic || decisionModel == null || !decisionModel.isAvailable()) {
            return heuristic;
        }
        try {
            DecisionResult r = decisionModel.decide(new DecisionRequest(
                    "loop_check",
                    newCall == null ? "" : String.valueOf(newCall),
                    List.of(), "", java.util.Map.of("threshold", threshold)));
            String a = r.answer() == null ? "" : r.answer().trim().toUpperCase(java.util.Locale.ROOT);
            if (a.equals("NO") || a.equals("FALSE")) return false;
            return true;
        } catch (RuntimeException e) {
            return heuristic; // fail-open
        }
    }
}
