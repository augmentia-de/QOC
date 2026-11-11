package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.adapter.sql.AbstractJdbcToolOutput;

import javax.sql.DataSource;
import java.time.Instant;

/** SQLite managed storage for bounded tool output and session artifacts. */
public final class SqliteToolOutput extends AbstractJdbcToolOutput {

    public SqliteToolOutput(DataSource dataSource, java.nio.file.Path directory) {
        super(dataSource, directory, SqliteDialect.INSTANCE);
    }

    @Override
    public void sweep(Instant olderThan) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "delete from tool_output where created_at < datetime(?, 'unixepoch')",
                    olderThan.getEpochSecond());
            return null;
        });
    }
}
