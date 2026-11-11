package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcCredentials;

import javax.sql.DataSource;

/** SQLite credential store; one active credential per integration. */
public final class SqliteCredentials extends AbstractJdbcCredentials {

    public SqliteCredentials(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }
}
