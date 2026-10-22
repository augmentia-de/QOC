package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SnapshotId;
import de.augmentia.oc.engine.runtime.session.revert.Revert;
import de.augmentia.oc.engine.runtime.session.revert.RevertResult;
import de.augmentia.oc.engine.testing.FakeSnapshotStore;
import de.augmentia.oc.engine.testing.InMemoryInstructions;
import de.augmentia.oc.engine.testing.InMemoryJournal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RevertTest {

    private InMemoryJournal journal;
    private FakeSnapshotStore snapshots;
    private InMemoryInstructions instructions;
    private Revert revert;
    private SessionId session;

    @BeforeEach
    void setUp() {
        journal = new InMemoryJournal();
        snapshots = new FakeSnapshotStore();
        instructions = new InMemoryInstructions();
        revert = new Revert(journal, Option.some(snapshots), Option.some(instructions));
        session = SessionId.create();
    }

    @Test
    void stageAndClearPublishEvents() {
        MessageId boundary = MessageId.create();

        assertThat(revert.stage(session, boundary)).isEqualTo(new RevertResult.Staged(boundary));
        assertThat(revert.clear(session)).isEqualTo(new RevertResult.Cleared());

        assertThat(journal.historyOfType(session.value(), "RevertStaged", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session.value(), "RevertCleared", Seq.zero())).hasSize(1);
    }

    @Test
    void commitRestoresSnapshotAndDeletesInstructions() {
        SnapshotId snapshot = snapshots.capture(List.of(Path.of("/work/a.txt")));
        MessageId boundary = MessageId.create();
        instructions.putEntry(session, new InstructionEntry("a", "1", "h1"));
        instructions.putEntry(session, new InstructionEntry("b", "2", "h2"));

        RevertResult result = revert.commit(session, boundary, Option.some(snapshot),
                List.of(Path.of("/work/a.txt")));

        assertThat(result).isInstanceOf(RevertResult.Committed.class);
        assertThat(snapshots.restored()).containsExactly(Path.of("/work/a.txt"));
        assertThat(instructions.entries(session)).isEmpty();
        assertThat(journal.historyOfType(session.value(), "RevertCommitted", Seq.zero())).hasSize(1);
    }

    @Test
    void commitWithoutSnapshotStillPublishesTheEvent() {
        RevertResult result = revert.commit(session, MessageId.create(), Option.none(), List.of());
        assertThat(result).isInstanceOf(RevertResult.Committed.class);
        assertThat(journal.historyOfType(session.value(), "RevertCommitted", Seq.zero())).hasSize(1);
    }

    @Test
    void restoreFailureIsAFailedResultAndPublishesNoCommit() {
        SnapshotId snapshot = snapshots.capture(List.of(Path.of("/work/a.txt")));
        snapshots.failRestore(true);

        RevertResult result = revert.commit(session, MessageId.create(), Option.some(snapshot),
                List.of(Path.of("/work/a.txt")));

        assertThat(result).isInstanceOf(RevertResult.Failed.class);
        assertThat(journal.historyOfType(session.value(), "RevertCommitted", Seq.zero())).isEmpty();
    }
}
