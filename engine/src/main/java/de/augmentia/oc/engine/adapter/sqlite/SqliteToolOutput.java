package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

/** PostgreSQL managed storage for bounded tool output. */
public final class SqliteToolOutput implements ToolOutputStore {

    private final DataSource dataSource;
    private final Path directory;

    public SqliteToolOutput(DataSource dataSource, Path directory) {
        if (dataSource == null || directory == null) {
            throw new IllegalArgumentException("dataSource and directory must not be null");
        }
        this.dataSource = dataSource;
        this.directory = directory;
    }

    @Override
    public Path save(String suffix, byte[] bytes) {
        if (suffix == null || suffix.isBlank()) throw new IllegalArgumentException("suffix must not be blank");
        Path path = directory.resolve("tool_" + suffix);
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection,
                    "insert into tool_output (path, content, created_at) values (?, ?, datetime('now')) " +
                            "on conflict (path) do update set content = excluded.content",
                    path.toString(), bytes == null ? new byte[0] : bytes);
            return null;
        });
        return path;
    }

    @Override
    public byte[] load(Path path) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select content from tool_output where path = ?")) {
            statement.setString(1, path.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new StoreFailure("unknown tool output: " + path);
                return resultSet.getBytes("content");
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read tool output", e);
        }
    }

    @Override
    public void sweep(Instant olderThan) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "delete from tool_output where created_at < datetime(?, 'unixepoch')",
                    olderThan.getEpochSecond());
            return null;
        });
    }
}
