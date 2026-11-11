package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcApprovals;

import javax.sql.DataSource;

/** SQLite stored approvals, unique per project/action/resource. */
public final class SqliteApprovals extends AbstractJdbcApprovals {

    public SqliteApprovals(DataSource dataSource) {
        super(dataSource, SqliteDialect.INSTANCE);
    }
}
