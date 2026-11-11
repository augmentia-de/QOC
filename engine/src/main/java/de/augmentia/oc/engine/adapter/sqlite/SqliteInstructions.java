package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcInstructions;

import javax.sql.DataSource;

/** SQLite instruction state, entries and blob store. */
public final class SqliteInstructions extends AbstractJdbcInstructions {

    public SqliteInstructions(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }
}
