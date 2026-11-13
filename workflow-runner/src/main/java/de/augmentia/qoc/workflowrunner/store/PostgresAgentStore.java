package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.oc.engine.adapter.sql.PostgresDialect;

import javax.sql.DataSource;

/** PostgreSQL agent specification store. */
public class PostgresAgentStore extends AbstractJdbcAgentStore {
    public PostgresAgentStore(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}