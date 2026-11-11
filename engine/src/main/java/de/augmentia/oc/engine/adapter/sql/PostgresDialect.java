package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.function.Function;

/** PostgreSQL dialect backed by the existing {@link Sql} helpers. */
public final class PostgresDialect implements SqlDialect {

    public static final PostgresDialect INSTANCE = new PostgresDialect();

    private PostgresDialect() {}

    @Override
    public <T> T transaction(DataSource dataSource, Function<Connection, T> work) {
        return Sql.transaction(dataSource, work);
    }

    @Override
    public String jsonCast() {
        return "::jsonb";
    }

    @Override
    public String nowExpression() {
        return "now()";
    }

    @Override
    public Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Sql.instant(resultSet, column);
    }
}
