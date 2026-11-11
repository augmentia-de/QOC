package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.function.Function;

/**
 * Minimal database dialect used by the shared JDBC base classes (H9).
 *
 * <p>Only the parts that genuinely differ between PostgreSQL and SQLite are
 * abstracted here; plain SQL statements and row mapping stay in the base
 * classes.</p>
 */
public interface SqlDialect {

    /** Runs {@code work} in a transaction and commits or rolls back. */
    <T> T transaction(DataSource dataSource, Function<Connection, T> work);

    /** SQL cast appended to a JSON {@code ?} parameter, e.g. {@code ::jsonb}. */
    String jsonCast();

    /** SQL expression for the current timestamp. */
    String nowExpression();

    /** Reads an {@link Instant} from a result-set column. */
    Instant instant(ResultSet resultSet, String column) throws SQLException;

    /** Default placeholder including the dialect JSON cast. */
    default String jsonParameter() {
        return "?" + jsonCast();
    }
}
