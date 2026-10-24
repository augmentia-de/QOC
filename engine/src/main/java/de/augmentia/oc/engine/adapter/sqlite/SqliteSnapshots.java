package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.FileDiff;
import de.augmentia.oc.engine.core.model.SnapshotId;
import de.augmentia.oc.engine.core.port.store.SnapshotStore;
import de.augmentia.oc.engine.core.port.world.FileSystem;

import javax.sql.DataSource;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL snapshot metadata and file contents; restore uses the FileSystem port. */
public final class SqliteSnapshots implements SnapshotStore {

    private final DataSource dataSource;
    private final FileSystem fileSystem;

    public SqliteSnapshots(DataSource dataSource, FileSystem fileSystem) {
        if (dataSource == null || fileSystem == null) {
            throw new IllegalArgumentException("snapshot dependencies must not be null");
        }
        this.dataSource = dataSource;
        this.fileSystem = fileSystem;
    }

    @Override
    public SnapshotId capture(List<Path> paths) {
        SnapshotId id = SnapshotId.create();
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "insert into snapshot (id, created_at) values (?, datetime('now'))", id.value());
            for (Path path : paths == null ? List.<Path>of() : paths) {
                byte[] content = readOrNull(path);
                Sqlite.execute(connection,
                        "insert into snapshot_file (snapshot_id, path, content) values (?, ?, ?)",
                        id.value(), path.toString(), content);
            }
            return null;
        });
        return id;
    }

    @Override
    public void restore(SnapshotId snapshot, List<Path> paths) {
        Map<String, byte[]> stored = files(snapshot);
        for (Path path : paths == null ? List.<Path>of() : paths) {
            byte[] content = stored.get(path.toString());
            if (content == null) continue;
            fileSystem.write(path, content);
        }
    }

    @Override
    public void delete(SnapshotId snapshot) {
        if (snapshot == null) return;
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "delete from snapshot_file where snapshot_id = ?", snapshot.value());
            Sqlite.execute(connection, "delete from snapshot where id = ?", snapshot.value());
            return null;
        });
    }

    @Override
    public List<FileDiff> diff(SnapshotId from, SnapshotId to) {
        Map<String, byte[]> oldFiles = files(from);
        Map<String, byte[]> newFiles = files(to);
        List<FileDiff> diffs = new ArrayList<>();
        for (String path : oldFiles.keySet()) {
            if (!newFiles.containsKey(path)) {
                diffs.add(new FileDiff(path, text(oldFiles.get(path)), "", FileDiff.Kind.DELETED));
            }
        }
        for (Map.Entry<String, byte[]> entry : newFiles.entrySet()) {
            byte[] old = oldFiles.get(entry.getKey());
            if (old == null) {
                diffs.add(new FileDiff(entry.getKey(), "", text(entry.getValue()), FileDiff.Kind.ADDED));
            } else if (!java.util.Arrays.equals(old, entry.getValue())) {
                diffs.add(new FileDiff(entry.getKey(), text(old), text(entry.getValue()),
                        FileDiff.Kind.MODIFIED));
            }
        }
        return List.copyOf(diffs);
    }

    private Map<String, byte[]> files(SnapshotId snapshot) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select path, content from snapshot_file where snapshot_id = ?")) {
            statement.setString(1, snapshot.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                Map<String, byte[]> files = new LinkedHashMap<>();
                while (resultSet.next()) {
                    files.put(resultSet.getString("path"), resultSet.getBytes("content"));
                }
                return files;
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read snapshot", e);
        }
    }

    private byte[] readOrNull(Path path) {
        try {
            return fileSystem.read(path);
        } catch (UncheckedIOException e) {
            return null;
        }
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
