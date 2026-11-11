package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** PostgreSQL tool-result cache. */
public final class SqlToolResultCache extends AbstractJdbcToolResultCache {

    public SqlToolResultCache(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
        if (cutoff == null) return 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "delete from tool_result_cache where created_at < ?")) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            return statement.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }
}
