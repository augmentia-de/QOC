package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcConversation;

import javax.sql.DataSource;

/** SQLite projection of the folded conversation. */
public final class SqliteConversation extends AbstractJdbcConversation {

    public SqliteConversation(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }
}
