package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * JDBC cleanup helpers for audit-related tables. Kept in the adapter layer so
 * the Quarkus wiring code stays free of java.sql.
 */
public final class AuditSql {

    private AuditSql() {
    }

    public static int deleteToolOutput(DataSource dataSource, Instant cutoff) {
        return deleteBefore(dataSource, "delete from tool_output where created_at < ?", cutoff);
    }

    public static int deleteSnapshots(DataSource dataSource, Instant cutoff) {
        return deleteBefore(dataSource, "delete from snapshot where created_at < ?", cutoff);
    }

    private static int deleteBefore(DataSource dataSource, String sql, Instant cutoff) {
        if (dataSource == null || cutoff == null) return 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            return statement.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }
}
