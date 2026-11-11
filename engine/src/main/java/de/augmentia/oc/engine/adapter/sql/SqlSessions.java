package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;

/** PostgreSQL session read model and inbox. */
public final class SqlSessions extends AbstractJdbcSessions {

    public SqlSessions(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}
