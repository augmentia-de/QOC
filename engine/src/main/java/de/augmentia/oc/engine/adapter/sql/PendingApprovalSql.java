package de.augmentia.oc.engine.adapter.sql;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC persistence for {@code pending_approval}. Kept in the adapter layer so
 * the Quarkus wiring/services stay free of java.sql.
 */
public final class PendingApprovalSql {

    private PendingApprovalSql() {
    }

    public static void persistPending(DataSource dataSource, String id, String sessionId,
                                      String action, String context, Instant createdAt,
                                      Instant deadline, String tokenHash, String channel) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into pending_approval " +
                             "(id, session_id, action, context, created_at, deadline_at, state, token, channel) " +
                             "values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, sessionId);
            statement.setString(3, action);
            statement.setString(4, context);
            statement.setTimestamp(5, Timestamp.from(createdAt));
            statement.setTimestamp(6, Timestamp.from(deadline));
            statement.setString(7, "PENDING");
            statement.setString(8, tokenHash);
            statement.setString(9, channel);
            statement.executeUpdate();
        }
    }

    public static String decision(DataSource dataSource, String requestId) throws SQLException {
        return readString(dataSource, "select decision from pending_approval where id = ?", requestId);
    }

    public static String feedback(DataSource dataSource, String requestId) throws SQLException {
        return readString(dataSource, "select feedback from pending_approval where id = ?", requestId);
    }

    public static String sessionIdForToken(DataSource dataSource, String tokenHash) throws SQLException {
        return readString(dataSource, "select session_id from pending_approval where token = ?", tokenHash);
    }

    public static String requestIdForToken(DataSource dataSource, String tokenHash) throws SQLException {
        return readString(dataSource, "select id from pending_approval where token = ?", tokenHash);
    }

    public static boolean updateDecision(DataSource dataSource, String requestId,
                                         String decision, String feedback) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update pending_approval set decision = ?, feedback = ?, state = 'DECIDED' " +
                             "where id = ? and state in ('PENDING','ORPHANED')")) {
            statement.setString(1, decision);
            statement.setString(2, feedback);
            statement.setString(3, requestId);
            return statement.executeUpdate() > 0;
        }
    }

    public static void markState(DataSource dataSource, String requestId, String state) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update pending_approval set state = ? where id = ?")) {
            statement.setString(1, state);
            statement.setString(2, requestId);
            statement.executeUpdate();
        }
    }

    public static List<de.augmentia.oc.engine.core.port.store.PendingApprovalStore.PendingApproval> pending(DataSource dataSource, String sessionId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id, session_id, action, context, created_at, deadline_at, state " +
                             "from pending_approval where session_id = ? " +
                             "and state in (?, ?) order by created_at")) {
            statement.setString(1, sessionId);
            statement.setString(2, "PENDING");
            statement.setString(3, "ORPHANED");
            try (ResultSet resultSet = statement.executeQuery()) {
                List<de.augmentia.oc.engine.core.port.store.PendingApprovalStore.PendingApproval> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(new de.augmentia.oc.engine.core.port.store.PendingApprovalStore.PendingApproval(
                            resultSet.getString("id"),
                            resultSet.getString("session_id"),
                            resultSet.getString("action"),
                            resultSet.getString("context"),
                            instant(resultSet, "created_at"),
                            instant(resultSet, "deadline_at"),
                            resultSet.getString("state")));
                }
                return List.copyOf(rows);
            }
        }
    }

    public static int recoverOrphans(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update pending_approval set state = ? where state = ?")) {
            statement.setString(1, "ORPHANED");
            statement.setString(2, "PENDING");
            return statement.executeUpdate();
        }
    }

    public static int deleteDecidedBefore(DataSource dataSource, Instant cutoff) throws SQLException {
        if (cutoff == null) return 0;
        try (Connection connection = dataSource.getConnection()) {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, created_at from pending_approval where state = 'DECIDED'");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Instant created = instant(resultSet, "created_at");
                    if (created != null && created.isBefore(cutoff)) {
                        ids.add(resultSet.getString("id"));
                    }
                }
            }
            int deleted = 0;
            for (String id : ids) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "delete from pending_approval where id = ?")) {
                    statement.setString(1, id);
                    deleted += statement.executeUpdate();
                }
            }
            return deleted;
        }
    }

    private static String readString(DataSource dataSource, String sql, String parameter) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        try {
            Timestamp timestamp = resultSet.getTimestamp(column);
            return timestamp == null ? null : timestamp.toInstant();
        } catch (SQLException e) {
            long value = resultSet.getLong(column);
            return resultSet.wasNull() ? null : Instant.ofEpochMilli(value);
        }
    }
}
