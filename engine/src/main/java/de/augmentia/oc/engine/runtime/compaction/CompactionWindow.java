package de.augmentia.oc.engine.runtime.compaction;

import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.Conversation;
import de.augmentia.oc.engine.core.port.store.InstructionStore;
import de.augmentia.oc.engine.core.eval.InstructionEpoch;
import de.augmentia.oc.engine.core.eval.TokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the compaction window from the delivered history and advances the
 * instruction epoch after a successful compaction.
 */
public final class CompactionWindow {

    public record Window(List<Message> toSummarize, List<Message> toKeep) {
        public Window {
            toSummarize = toSummarize == null ? List.of() : List.copyOf(toSummarize);
            toKeep = toKeep == null ? List.of() : List.copyOf(toKeep);
        }
    }

    private CompactionWindow() {}

    /**
     * Splits the history: everything except the last {@code keep} messages is
     * summarized. At least one message must remain (the last user turn).
     */
    public static Window split(List<Message> history, int keep) {
        return split(history, new CompactionPolicy(Math.max(1, keep), 0, 0), 0);
    }

    /**
     * Token-aware split. Keeps at least {@code policy.keepMessages()} messages
     * and roughly {@code policy.keepRecentTokens()}, then moves the boundary
     * back to a user turn so a tool call and its result are never separated.
     */
    public static Window split(List<Message> history, CompactionPolicy policy, int contextWindow) {
        if (history == null || history.isEmpty()) return new Window(List.of(), List.of());
        if (policy == null) policy = CompactionPolicy.DEFAULT;

        int keepCount = Math.max(1, Math.min(policy.keepMessages(), history.size()));
        int split = history.size() - keepCount;

        if (contextWindow > 0 && policy.keepRecentTokens() > 0) {
            int tokens = 0;
            while (split > 0 && tokens < policy.keepRecentTokens()) {
                split--;
                tokens += TokenEstimator.estimate(history.get(split));
            }
            int budget = Math.max(1, contextWindow - policy.reserveTokens());
            while (split > 0 && tokens > budget) {
                split--;
                tokens += TokenEstimator.estimate(history.get(split));
            }
        }

        // Keep whole turns: never split between a user input and the assistant
        // turn it opened. A turn starts at a UserMessage; move the split back
        // to the nearest such boundary. If none exists, do not split.
        while (split > 0 && !(history.get(split) instanceof Message.UserMessage)) {
            split--;
        }
        if (split <= 0) {
            return new Window(List.of(), List.copyOf(history));
        }
        return new Window(history.subList(0, split), history.subList(split, history.size()));
    }
}
