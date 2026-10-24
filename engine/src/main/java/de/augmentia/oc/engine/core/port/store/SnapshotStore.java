package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.FileDiff;
import de.augmentia.oc.engine.core.model.SnapshotId;

import java.nio.file.Path;
import java.util.List;

/** Snapshot boundary for revert. */
public interface SnapshotStore {

    SnapshotId capture(List<Path> paths);

    void restore(SnapshotId snapshot, List<Path> paths);

    /** Removes a snapshot and its stored files. */
    void delete(SnapshotId snapshot);

    List<FileDiff> diff(SnapshotId from, SnapshotId to);
}
