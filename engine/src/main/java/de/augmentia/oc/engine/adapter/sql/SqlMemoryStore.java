package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;

/** PostgreSQL memory store. */
public final class SqlMemoryStore extends AbstractJdbcMemoryStore {

    public SqlMemoryStore(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}
