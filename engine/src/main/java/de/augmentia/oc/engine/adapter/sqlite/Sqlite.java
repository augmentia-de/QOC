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

    static <T> T transaction(DataSource dataSource, Function<Connection, T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new StoreFailure("database transaction failed", e);
        }
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
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            throw new de.augmentia.oc.engine.core.error.StoreFailure("cannot enable sqlite wal", e);
        }
    }
}
