package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.FileDiff;
import de.augmentia.oc.engine.core.model.SnapshotId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySnapshotStoreTest {

    @TempDir
    Path workspace;

    @Test
    void captureAndRestoreFileContents() throws Exception {
        Path file = workspace.resolve("src/app.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "before");

        RuntimeState state = new RuntimeState(new MemoryBudget(1 << 20));
        InMemorySnapshotStore store = new InMemorySnapshotStore(state);

        SnapshotId snapshot = store.capture(List.of(file));
        Files.writeString(file, "after");

        store.restore(snapshot, List.of(file));

        assertThat(Files.readString(file)).isEqualTo("before");
        assertThat(state.budget.usedBytes()).isEqualTo("before".length());
    }

    @Test
    void diffReportsAddedModifiedAndDeletedFiles() throws Exception {
        Path modified = workspace.resolve("modified.txt");
        Path deleted = workspace.resolve("deleted.txt");
        Files.writeString(modified, "old");
        Files.writeString(deleted, "gone");

        RuntimeState state = new RuntimeState(new MemoryBudget(1 << 20));
        InMemorySnapshotStore store = new InMemorySnapshotStore(state);

        SnapshotId before = store.capture(List.of(modified, deleted));
        Files.writeString(modified, "new");
        Files.delete(deleted);
        Path added = workspace.resolve("added.txt");
        Files.writeString(added, "fresh");
        SnapshotId after = store.capture(List.of(modified, added));

        assertThat(store.diff(before, after))
                .extracting(FileDiff::path, FileDiff::kind)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(modified.toString(), FileDiff.Kind.MODIFIED),
                        org.assertj.core.groups.Tuple.tuple(deleted.toString(), FileDiff.Kind.DELETED),
                        org.assertj.core.groups.Tuple.tuple(added.toString(), FileDiff.Kind.ADDED));
    }

    @Test
    void deleteReleasesBudgetAndRemovesSnapshot() throws Exception {
        Path file = workspace.resolve("value.txt");
        Files.writeString(file, "12345");

        RuntimeState state = new RuntimeState(new MemoryBudget(1 << 20));
        InMemorySnapshotStore store = new InMemorySnapshotStore(state);

        SnapshotId snapshot = store.capture(List.of(file));
        assertThat(state.budget.usedBytes()).isEqualTo(5);

        store.delete(snapshot);

        assertThat(state.budget.usedBytes()).isZero();
        assertThat(store.diff(snapshot, snapshot)).isEmpty();
    }
}
