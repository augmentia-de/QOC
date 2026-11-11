package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcToolResultCache;
import de.augmentia.oc.engine.core.error.StoreFailure;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** SQLite tool-result cache. */
public final class SqliteToolResultCache extends AbstractJdbcToolResultCache {

    public SqliteToolResultCache(javax.sql.DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
        if (cutoff == null) return 0;
        return Sqlite.transaction(dataSource, connection -> {
            List<String> keys = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select idempotency_key, created_at from tool_result_cache");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Instant created = Sqlite.instant(resultSet, "created_at");
                    if (created != null && created.isBefore(cutoff)) {
                        keys.add(resultSet.getString("idempotency_key"));
                    }
                }
            } catch (SQLException e) {
                throw new StoreFailure("cannot scan tool result cache", e);
            }
            for (String key : keys) {
                Sqlite.execute(connection, "delete from tool_result_cache where idempotency_key = ?", key);
            }
            return keys.size();
        });
    }
}
