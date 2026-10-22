package de.augmentia.oc.engine.runtime.session.revert;

import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SnapshotId;

import java.nio.file.Path;
import java.util.List;

/** Outcome of a revert operation. */
public sealed interface RevertResult {

    record Staged(MessageId boundary) implements RevertResult {}

    record Cleared() implements RevertResult {}

    record Committed(Option<SnapshotId> snapshot, List<Path> restored) implements RevertResult {
        public Committed {
            if (snapshot == null) snapshot = Option.none();
            restored = restored == null ? List.of() : List.copyOf(restored);
        }
    }

    record Failed(String message) implements RevertResult {
        public Failed {
            if (message == null) message = "";
        }
    }
}
