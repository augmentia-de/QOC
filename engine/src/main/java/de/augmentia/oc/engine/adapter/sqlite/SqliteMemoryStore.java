package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcMemoryStore;

import javax.sql.DataSource;

/** SQLite memory store. */
public final class SqliteMemoryStore extends AbstractJdbcMemoryStore {

    public SqliteMemoryStore(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }
}