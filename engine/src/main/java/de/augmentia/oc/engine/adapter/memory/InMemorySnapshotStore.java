package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.FileDiff;
import de.augmentia.oc.engine.core.model.SnapshotId;
import de.augmentia.oc.engine.core.port.store.SnapshotStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory snapshot store: keeps the file contents of each snapshot in a map.
 *
 * <p>Snapshots are bounded by the shared {@link MemoryBudget}. A capture that
 * would exceed the budget fails with {@link MemoryLimitExceeded} and releases
 * everything it reserved, so no partial snapshot is left behind.</p>
 */
public final class InMemorySnapshotStore implements SnapshotStore {

    private final RuntimeState state;

    public InMemorySnapshotStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    @Override
    public SnapshotId capture(List<Path> paths) {
        SnapshotId id = SnapshotId.create();
        Map<Path, byte[]> contents = new LinkedHashMap<>();
        long reserved = 0;
        try {
            for (Path path : paths == null ? List.<Path>of() : paths) {
                if (path == null || !Files.isRegularFile(path)) continue;
                byte[] bytes = Files.readAllBytes(path);
                state.budget.reserve(bytes.length);
                reserved += bytes.length;
                contents.put(path, bytes);
            }
        } catch (IOException e) {
            state.budget.release(reserved);
            throw new StoreFailure("cannot capture snapshot files", e);
        } catch (RuntimeException e) {
            state.budget.release(reserved);
            throw e;
        }
        state.snapshots.put(id, Map.copyOf(contents));
        return id;
    }

    @Override
    public void restore(SnapshotId snapshot, List<Path> paths) {
        Map<Path, byte[]> stored = state.snapshots.get(snapshot);
        if (stored == null) return;
        try {
            for (Path path : paths == null ? List.<Path>of() : paths) {
                byte[] bytes = stored.get(path);
                if (bytes == null) continue;
                Path parent = path.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(path, bytes);
            }
        } catch (IOException e) {
            throw new StoreFailure("cannot restore snapshot files", e);
        }
    }

    @Override
    public void delete(SnapshotId snapshot) {
        if (snapshot == null) return;
        Map<Path, byte[]> removed = state.snapshots.remove(snapshot);
        if (removed == null) return;
        for (byte[] bytes : removed.values()) {
            state.budget.release(bytes.length);
        }
    }

    @Override
    public List<FileDiff> diff(SnapshotId from, SnapshotId to) {
        Map<Path, byte[]> oldFiles = state.snapshots.getOrDefault(from, Map.of());
        Map<Path, byte[]> newFiles = state.snapshots.getOrDefault(to, Map.of());
        List<FileDiff> diffs = new ArrayList<>();
        for (Path path : oldFiles.keySet()) {
            if (!newFiles.containsKey(path)) {
                diffs.add(new FileDiff(path.toString(), text(oldFiles.get(path)), "",
                        FileDiff.Kind.DELETED));
            }
        }
        for (Map.Entry<Path, byte[]> entry : newFiles.entrySet()) {
            byte[] old = oldFiles.get(entry.getKey());
            if (old == null) {
                diffs.add(new FileDiff(entry.getKey().toString(), "", text(entry.getValue()),
                        FileDiff.Kind.ADDED));
            } else if (!java.util.Arrays.equals(old, entry.getValue())) {
                diffs.add(new FileDiff(entry.getKey().toString(), text(old), text(entry.getValue()),
                        FileDiff.Kind.MODIFIED));
            }
        }
        return List.copyOf(diffs);
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
