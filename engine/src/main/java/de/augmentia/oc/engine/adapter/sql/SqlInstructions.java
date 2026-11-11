package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;

/** PostgreSQL instruction state, entries and blob store. */
public final class SqlInstructions extends AbstractJdbcInstructions {

    public SqlInstructions(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}
