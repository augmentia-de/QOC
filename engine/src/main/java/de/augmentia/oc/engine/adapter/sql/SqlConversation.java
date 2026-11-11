package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;

/** PostgreSQL projection of the folded conversation. */
public final class SqlConversation extends AbstractJdbcConversation {

    public SqlConversation(DataSource dataSource) {
        super(dataSource, PostgresDialect.INSTANCE);
    }
}
