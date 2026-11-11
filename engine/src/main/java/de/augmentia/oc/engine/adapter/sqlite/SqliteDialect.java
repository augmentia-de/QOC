package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.SqlDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.function.Function;

/** SQLite dialect backed by the existing {@link Sqlite} helpers. */
public final class SqliteDialect implements SqlDialect {

    public static final SqliteDialect INSTANCE = new SqliteDialect();

    private SqliteDialect() {}

    @Override
    public <T> T transaction(DataSource dataSource, Function<Connection, T> work) {
        return Sqlite.transaction(dataSource, work);
    }

    @Override
    public String jsonCast() {
        return "";
    }

    @Override
    public String nowExpression() {
        return "datetime('now')";
    }

    @Override
    public Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Sqlite.instant(resultSet, column);
    }
}
