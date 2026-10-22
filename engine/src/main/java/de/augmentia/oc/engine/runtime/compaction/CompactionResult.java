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

    record Failed(FailureKind kind, String message) implements CompactionResult {
        public Failed {
            if (message == null) message = "";
        }
    }
}
