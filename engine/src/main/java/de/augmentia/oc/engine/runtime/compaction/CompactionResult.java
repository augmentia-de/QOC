package de.augmentia.oc.engine.runtime.compaction;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.model.Seq;

/** Outcome of one compaction attempt. */
public sealed interface CompactionResult {

    record Compacted(String summary, Seq epochStart) implements CompactionResult {
        public Compacted {
            if (summary == null) summary = "";
            if (epochStart == null) throw new IllegalArgumentException("epochStart must not be null");
        }
    }

    record AlreadyInProgress() implements CompactionResult {}

    /** There is not enough history to compact (all messages are kept). */
    record NothingToDo() implements CompactionResult {}

    record Failed(FailureKind kind, String message) implements CompactionResult {
        public Failed {
            if (message == null) message = "";
        }
    }

    /**
     * The instruction state points at a previous summary that cannot be read.
     * The raw history is untouched, so a later repair or retry is safe.
     */
    record PredecessorMissing(String message) implements CompactionResult {
        public PredecessorMissing {
            if (message == null) message = "";
        }
    }
}
