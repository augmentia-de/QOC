package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcClaims;

import javax.sql.DataSource;

/** SQLite claim store with a durable resume counter. */
public final class SqliteClaims extends AbstractJdbcClaims {

    public SqliteClaims(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }
}
