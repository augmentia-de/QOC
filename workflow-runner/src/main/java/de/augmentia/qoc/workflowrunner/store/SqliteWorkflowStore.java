package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.oc.engine.adapter.sqlite.SqliteDialect;

import javax.sql.DataSource;

/** SQLite workflow specification store. */
public class SqliteWorkflowStore extends AbstractJdbcWorkflowStore {

    public SqliteWorkflowStore(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }
}
