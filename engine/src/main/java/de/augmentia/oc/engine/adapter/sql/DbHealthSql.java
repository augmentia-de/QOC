package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Health check helper for a JDBC data source. Kept in the adapter layer so
 * the Quarkus wiring code stays free of java.sql.
 */
public final class DbHealthSql {

    private DbHealthSql() {
    }

    public static boolean isHealthy(DataSource dataSource, int timeoutSeconds) {
        if (dataSource == null) return true;
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(Math.max(1, timeoutSeconds));
        } catch (Exception e) {
            return false;
        }
    }
}
