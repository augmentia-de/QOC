package de.augmentia.oc.engine.runtime.session.revert;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SnapshotId;
import de.augmentia.oc.engine.core.port.store.InstructionStore;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.core.port.store.SnapshotStore;

import java.nio.file.Path;
import java.util.List;

/**
 * Revert: stage a boundary, commit the snapshot, delete instructions
 * conditionally. The event log stays append-only; the revert is a new event.
 */
public final class Revert {

    private final Journal journal;
    private final Option<SnapshotStore> snapshots;
    private final Option<InstructionStore> instructions;

    public Revert(Journal journal, Option<SnapshotStore> snapshots, Option<InstructionStore> instructions) {
        if (journal == null) throw new IllegalArgumentException("journal must not be null");
        this.journal = journal;
        this.snapshots = snapshots == null ? Option.none() : snapshots;
        this.instructions = instructions == null ? Option.none() : instructions;
    }

    public RevertResult stage(SessionId session, MessageId boundary) {
        if (session == null || boundary == null) {
            throw new IllegalArgumentException("session and boundary must not be null");
        }
        journal.append(new EventDraft(session.value(), 1, new SessionEvent.RevertStaged(session, boundary)));
        return new RevertResult.Staged(boundary);
    }

    public RevertResult clear(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        journal.append(new EventDraft(session.value(), 1, new SessionEvent.RevertCleared(session)));
        return new RevertResult.Cleared();
    }

    public RevertResult commit(SessionId session, MessageId boundary, Option<SnapshotId> snapshot,
                               List<Path> paths) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        if (snapshot == null) snapshot = Option.none();
        List<Path> affected = paths == null ? List.of() : List.copyOf(paths);

        if (snapshot.isPresent() && snapshots.isPresent()) {
            try {
                snapshots.orElseThrow().restore(snapshot.orElseThrow(), affected);
            } catch (StoreFailure e) {
                return new RevertResult.Failed("snapshot restore failed");
            }
        }

        deleteInstructions(session);
        journal.append(new EventDraft(session.value(), 1,
                new SessionEvent.RevertCommitted(session, boundary == null ? Option.none() : Option.some(boundary))));
        return new RevertResult.Committed(snapshot, affected);
    }

    private void deleteInstructions(SessionId session) {
        if (instructions.isEmpty()) return;
        InstructionStore store = instructions.orElseThrow();
        for (InstructionEntry entry : store.entries(session)) {
            store.removeEntry(session, entry.key());
        }
    }
}
