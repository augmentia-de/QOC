package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;

/** PostgreSQL claim store with a durable resume counter. */
public final class SqlClaims extends AbstractJdbcClaims {

    public SqlClaims(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}
