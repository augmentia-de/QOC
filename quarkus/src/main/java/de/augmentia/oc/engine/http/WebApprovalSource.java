package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Human-in-the-loop approval source for the web UI. The waiting future is
 * process-local, but the request itself is persisted so it is visible after a
 * restart and can be reconciled/expired by operators.
 */
@ApplicationScoped
public class WebApprovalSource implements ApprovalSource {

    private static final long TIMEOUT_SECONDS = 300;
    private static final String PENDING = "PENDING";
    private static final String ORPHANED = "ORPHANED";
    private static final String DECIDED = "DECIDED";
    private static final String EXPIRED = "EXPIRED";

    private final Map<String, CompletableFuture<ApprovalResult>> pending =
            new ConcurrentHashMap<>();

    @Inject
    DataSource dataSource;

    @Override
    public ApprovalResult request(PermissionRequestId requestId, SessionId sessionId,
                                  String action, String context) {
        String key = key(sessionId.value(), requestId.value());
        Instant now = Instant.now();
        Instant deadline = now.plusSeconds(TIMEOUT_SECONDS);
        try {
            persistPending(requestId.value(), sessionId.value(), action, context, now, deadline);
        } catch (SQLException e) {
            // Fail closed: an approval that cannot be made durable must not be
            // silently approved or left in a process-local limbo.
            return new ApprovalResult.Denied(Option.some("approval store unavailable"));
        }

        CompletableFuture<ApprovalResult> future = new CompletableFuture<>();
        pending.put(key, future);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            markExpired(requestId.value());
            return new ApprovalResult.Denied(Option.some("approval timed out"));
        } catch (Exception e) {
            markExpired(requestId.value());
            return new ApprovalResult.Denied(Option.some("approval failed: " + e.getMessage()));
        } finally {
            pending.remove(key);
        }
    }

    /** Resolves a pending request. Returns true if it was pending or persisted. */
    public boolean resolve(String sessionId, String requestId, ApprovalResult result) {
        String key = key(sessionId, requestId);
        CompletableFuture<ApprovalResult> future = pending.remove(key);
        boolean completed = future != null && future.complete(result);
        boolean persisted = markDecided(requestId);
        return completed || persisted;
    }

    public boolean hasPending(String sessionId, String requestId) {
        return pending.containsKey(key(sessionId, requestId));
    }

    /** Pending rows, including orphaned rows from a previous process. */
    public List<PendingApproval> pending(String sessionId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id, session_id, action, context, created_at, deadline_at, state " +
                             "from pending_approval where session_id = ? " +
                             "and state in (?, ?) order by created_at")) {
            statement.setString(1, sessionId);
            statement.setString(2, PENDING);
            statement.setString(3, ORPHANED);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<PendingApproval> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(new PendingApproval(
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
        } catch (SQLException e) {
            return List.of();
        }
    }

    private void persistPending(String requestId, String sessionId, String action,
                                String context, Instant createdAt, Instant deadline)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into pending_approval " +
                             "(id, session_id, action, context, created_at, deadline_at, state) " +
                             "values (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, requestId);
            statement.setString(2, sessionId);
            statement.setString(3, action);
            statement.setString(4, context);
            statement.setTimestamp(5, Timestamp.from(createdAt));
            statement.setTimestamp(6, Timestamp.from(deadline));
            statement.setString(7, PENDING);
            statement.executeUpdate();
        }
    }

    private boolean markDecided(String requestId) {
        return updateState(requestId, DECIDED);
    }

    private void markExpired(String requestId) {
        updateState(requestId, EXPIRED);
    }

    /** Marks approvals from a previous process as orphaned, not silently lost. */
    public int recoverOrphans() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update pending_approval set state = ? where state = ?")) {
            statement.setString(1, ORPHANED);
            statement.setString(2, PENDING);
            return statement.executeUpdate();
        } catch (SQLException e) {
            return 0;
        }
    }

    private boolean updateState(String requestId, String state) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update pending_approval set state = ? where id = ? and state in (?, ?)")) {
            statement.setString(1, state);
            statement.setString(2, requestId);
            statement.setString(3, PENDING);
            statement.setString(4, ORPHANED);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        if (timestamp != null) return timestamp.toInstant();
        long millis = resultSet.getLong(column);
        return resultSet.wasNull() ? Instant.EPOCH : Instant.ofEpochMilli(millis);
    }

    private static String key(String sessionId, String requestId) {
        return sessionId + ":" + requestId;
    }

    public record PendingApproval(String id, String sessionId, String action,
                                  String context, Instant createdAt, Instant deadline,
                                  String state) {}
}
