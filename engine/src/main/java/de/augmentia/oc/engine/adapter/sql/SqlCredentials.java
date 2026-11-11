package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;

/** PostgreSQL credential store; one active credential per integration. */
public final class SqlCredentials extends AbstractJdbcCredentials {

    public SqlCredentials(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}
