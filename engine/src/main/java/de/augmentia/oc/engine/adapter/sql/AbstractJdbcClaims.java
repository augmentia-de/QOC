package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ClaimStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shared JDBC claim store with a durable resume counter (H9). */
public abstract class AbstractJdbcClaims implements ClaimStore {

    private final DataSource dataSource;
    private final SqlDialect dialect;

    protected AbstractJdbcClaims(DataSource dataSource, SqlDialect dialect) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        if (dialect == null) throw new IllegalArgumentException("dialect must not be null");
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public Option<Claim> acquire(SessionId session, Instant at) {
        return dialect.transaction(dataSource, connection -> {
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into session_claim (session_id, claimed_at, resume_attempts) " +
                            "values (?, ?, 0) on conflict (session_id) do nothing")) {
                statement.setString(1, session.value());
                statement.setTimestamp(2, Timestamp.from(at));
                inserted = statement.executeUpdate();
            } catch (SQLException e) {
                throw new StoreFailure("cannot acquire claim", e);
            }
            if (inserted == 0) return Option.none();

            int attempts = nextAttempt(connection, session);
            Sql.execute(connection,
                    "update session_claim set resume_attempts = ? where session_id = ?",
                    attempts, session.value());
            return Option.some(new Claim(session, at, attempts));
        });
    }

    private int nextAttempt(Connection connection, SessionId session) {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into resume_counter (session_id, attempts) values (?, 1) " +
                        "on conflict (session_id) do update set attempts = resume_counter.attempts + 1 " +
                        "returning attempts")) {
            statement.setString(1, session.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) - 1;
            }
        } catch (SQLException e) {
            throw new StoreFailure("cannot update resume counter", e);
        }
    }

    @Override
    public void release(SessionId session) {
        dialect.transaction(dataSource, connection -> {
            Sql.execute(connection, "delete from session_claim where session_id = ?", session.value());
            return null;
        });
    }

    @Override
    public List<Claim> open() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select session_id, claimed_at, resume_attempts from session_claim")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Claim> claims = new ArrayList<>();
                while (resultSet.next()) {
                    claims.add(new Claim(SessionId.parse(resultSet.getString("session_id")),
                            dialect.instant(resultSet, "claimed_at"),
                            resultSet.getInt("resume_attempts")));
                }
                return List.copyOf(claims);
            }
        } catch (SQLException e) {
            throw new StoreFailure("cannot read open claims", e);
        }
    }
}
