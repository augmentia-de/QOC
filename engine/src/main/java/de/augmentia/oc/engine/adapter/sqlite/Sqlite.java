package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Small JDBC helpers; SQL adapters are the only place allowed to use java.sql. */
public final class Sqlite {

    private Sqlite() {}

    private static final Object SQLITE_WRITE_LOCK = new Object();

    static <T> T transaction(DataSource dataSource, Function<Connection, T> work) {
        if (dataSource == null || work == null) {
            throw new IllegalArgumentException("data source and work must not be null");
        }
        int attempts = 0;
        while (true) {
            // SQLite has a single writer. Serialising transactions in this JVM
            // avoids SQLITE_BUSY / SQLITE_BUSY_SNAPSHOT under concurrent requests.
            // A short retry loop covers writers in other processes or races that
            // still slip through.
            synchronized (SQLITE_WRITE_LOCK) {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (Statement pragma = connection.createStatement()) {
                        pragma.execute("PRAGMA busy_timeout=5000");
                    }
                    try {
                        T result = work.apply(connection);
                        connection.commit();
                        return result;
                    } catch (RuntimeException e) {
                        connection.rollback();
                        throw e;
                    }
                } catch (SQLException e) {
                    if (isBusy(e) && attempts < 5) {
                        attempts++;
                        try {
                            Thread.sleep(10L * attempts);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new StoreFailure("sqlite transaction interrupted", interrupted);
                        }
                        continue;
                    }
                    throw new StoreFailure("database transaction failed", e);
                }
            }
        }
    }

    private static boolean isBusy(SQLException e) {
        if (e == null) return false;
        int code = e.getErrorCode();
        if (code == 5 || code == 6) return true;
        String message = e.getMessage();
        return message != null
                && (message.toLowerCase().contains("busy")
                        || message.toLowerCase().contains("locked"));
    }

    static void execute(Connection connection, String sql, Object... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new StoreFailure("database statement failed", e);
        }
    }

    static void bind(PreparedStatement statement, Object... parameters) {
        try {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
        } catch (SQLException e) {
            throw new StoreFailure("cannot bind statement", e);
        }
    }

    static String json(JsonValue value) {
        return Json.encode(value);
    }

    static JsonValue parse(String text) {
        if (text == null) return JsonValue.ofNull();
        Result<JsonValue> parsed = Json.parse(text);
        if (parsed instanceof Result.Err<JsonValue> error) {
            throw new StoreFailure("invalid json in database: " + error.error().message());
        }
        return parsed.valueOrNull();
    }

    static Instant instant(ResultSet resultSet, String column) throws SQLException {
        try {
            java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
            return timestamp == null ? null : timestamp.toInstant();
        } catch (SQLException e) {
            long value = resultSet.getLong(column);
            if (resultSet.wasNull()) return null;
            // SQLite may persist java.sql.Timestamp as epoch millis.
            return java.time.Instant.ofEpochMilli(value);
        }
    }

    static Map<String, String> stringMap(JsonValue value) {
        if (!(value instanceof JsonValue.Obj object)) return Map.of();
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : object.values().entrySet()) {
            if (entry.getValue() instanceof JsonValue.Str str) {
                map.put(entry.getKey(), str.value());
            }
        }
        return Map.copyOf(map);
    }

    public static void enableWal(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            throw new de.augmentia.oc.engine.core.error.StoreFailure("cannot enable sqlite wal", e);
        }
    }
}
