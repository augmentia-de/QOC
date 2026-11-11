package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.oc.engine.adapter.sql.PostgresDialect;

import javax.sql.DataSource;

/** PostgreSQL workflow specification store. */
public class PostgresWorkflowStore extends AbstractJdbcWorkflowStore {

    public PostgresWorkflowStore(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}
