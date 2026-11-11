package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.IdempotencyKey;
import de.augmentia.oc.engine.core.port.store.ToolResultCache;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Shared JDBC implementation of {@link ToolResultCache} (H9). */
public abstract class AbstractJdbcToolResultCache implements ToolResultCache {

    protected final DataSource dataSource;
    protected final SqlDialect dialect;

    protected AbstractJdbcToolResultCache(DataSource dataSource, SqlDialect dialect) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        if (dialect == null) throw new IllegalArgumentException("dialect must not be null");
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public Optional<JsonValue> find(IdempotencyKey key) {
        if (key == null) return Optional.empty();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select outcome from tool_result_cache where idempotency_key = ?")) {
            statement.setString(1, key.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Optional.empty();
                Result<JsonValue> parsed = Json.parse(resultSet.getString("outcome"));
                return parsed instanceof Result.Ok<JsonValue> ok
                        ? Optional.ofNullable(ok.value()) : Optional.empty();
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
    }

    @Override
    public void store(IdempotencyKey key, String sessionId, String toolName, JsonValue outcome) {
        if (key == null || outcome == null) return;
        String json = Json.encode(outcome);
        dialect.transaction(dataSource, connection -> {
            Sql.execute(connection,
                    "insert into tool_result_cache (idempotency_key, session_id, tool_name, outcome) " +
                            "values (?, ?, ?, " + dialect.jsonParameter() + ") on conflict (idempotency_key) do nothing",
                    key.value(), sessionId, toolName, json);
            return null;
        });
    }

    @Override
    public void delete(IdempotencyKey key) {
        if (key == null) return;
        dialect.transaction(dataSource, connection -> {
            Sql.execute(connection, "delete from tool_result_cache where idempotency_key = ?", key.value());
            return null;
        });
    }

    @Override
    public void deleteBySession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        dialect.transaction(dataSource, connection -> {
            Sql.execute(connection, "delete from tool_result_cache where session_id = ?", sessionId);
            return null;
        });
    }
}
