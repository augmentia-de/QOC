package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.oc.engine.adapter.sqlite.SqliteDialect;

import javax.sql.DataSource;

/** SQLite agent specification store. */
public class SqliteAgentStore extends AbstractJdbcAgentStore {
    public SqliteAgentStore(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }
}