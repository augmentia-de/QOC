package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;

/** PostgreSQL stored approvals, unique per project/action/resource. */
public final class SqlApprovals extends AbstractJdbcApprovals {

    public SqlApprovals(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}
