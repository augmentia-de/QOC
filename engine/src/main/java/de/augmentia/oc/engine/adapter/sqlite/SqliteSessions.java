package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcSessions;
import de.augmentia.oc.engine.core.error.StoreFailure;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** SQLite session read model and inbox. */
public final class SqliteSessions extends AbstractJdbcSessions {

    public SqliteSessions(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }

    @Override
    public void setIdleOutcome(de.augmentia.oc.engine.core.model.SessionId id, String outcome) {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "update session set idle_outcome = ? where id = ?")) {
            ps.setString(1, outcome);
            ps.setString(2, id.value());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StoreFailure("setIdleOutcome failed", e);
        }
    }
}
