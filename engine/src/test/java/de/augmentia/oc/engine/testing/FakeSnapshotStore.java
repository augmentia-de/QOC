package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.FileDiff;
import de.augmentia.oc.engine.core.model.SnapshotId;
import de.augmentia.oc.engine.core.port.store.SnapshotStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory snapshots recording captured and restored paths. */
public final class FakeSnapshotStore implements SnapshotStore {

    private final Map<SnapshotId, List<Path>> snapshots = new LinkedHashMap<>();
    private final List<Path> restored = new ArrayList<>();
    private boolean failRestore;

    @Override
    public synchronized SnapshotId capture(List<Path> paths) {
        SnapshotId id = SnapshotId.create();
        snapshots.put(id, paths == null ? List.of() : List.copyOf(paths));
        return id;
    }

    @Override
    public synchronized void restore(SnapshotId snapshot, List<Path> paths) {
        if (failRestore) throw new StoreFailure("restore failed");
        restored.addAll(paths == null ? List.of() : paths);
    }

    @Override
    public synchronized List<FileDiff> diff(SnapshotId from, SnapshotId to) {
        return List.of();
    }

    public synchronized List<Path> restored() {
        return List.copyOf(restored);
    }

    public synchronized void failRestore(boolean fail) {
        failRestore = fail;
    }
}
