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
        if (history == null || history.isEmpty()) return new Window(List.of(), List.of());
        int keepCount = Math.max(1, Math.min(keep, history.size()));
        int split = history.size() - keepCount;
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
