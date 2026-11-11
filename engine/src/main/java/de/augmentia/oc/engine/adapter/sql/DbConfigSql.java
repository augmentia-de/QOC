package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.error.StoreFailure;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Small database configuration helpers. Kept in the adapter layer so the
 * Quarkus wiring code stays free of java.sql.
 */
public final class DbConfigSql {

    private DbConfigSql() {
    }

    public static void configureSqlite(DataSource dataSource) {
        if (dataSource == null) return;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            throw new StoreFailure("cannot configure sqlite", e);
        }
    }
}
