package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.model.SessionId;

import javax.sql.DataSource;
import java.time.Instant;

/** PostgreSQL managed storage for bounded tool output and session artifacts. */
public final class SqlToolOutput extends AbstractJdbcToolOutput {

    public SqlToolOutput(DataSource dataSource, java.nio.file.Path directory) {
        super(dataSource, directory, PostgresDialect.INSTANCE);
    }

    @Override
    public void sweep(Instant olderThan) {
        dialect.transaction(dataSource, connection -> {
            Sql.execute(connection, "delete from tool_output where created_at < ?",
                    java.sql.Timestamp.from(olderThan));
            return null;
        });
    }
}
