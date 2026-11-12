package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.FileDiff;
import de.augmentia.oc.engine.core.model.SnapshotId;

import java.nio.file.Path;
import java.util.List;

/** No-op snapshot store for minimal (in-memory) mode: no revert snapshots. */
public final class NoOpSnapshotStore implements de.augmentia.oc.engine.core.port.store.SnapshotStore {
    @Override public SnapshotId capture(List<Path> paths) { return SnapshotId.create(); }
    @Override public void restore(SnapshotId snapshot, List<Path> paths) { /* no-op */ }
    @Override public void delete(SnapshotId snapshot) { /* no-op */ }
    @Override public List<FileDiff> diff(SnapshotId from, SnapshotId to) { return List.of(); }
}