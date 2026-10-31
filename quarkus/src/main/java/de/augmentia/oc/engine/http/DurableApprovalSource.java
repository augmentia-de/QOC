package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;
import de.augmentia.oc.engine.core.port.human.NotificationChannel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Durable, asynchronous approval source. Persists the request (with a single-use
 * token), sends a notification via a {@link NotificationChannel}, and polls the
 * database for a decision instead of holding an in-memory future.
 */
@ApplicationScoped
public class DurableApprovalSource implements ApprovalSource {

    private static final Logger LOG = Logger.getLogger(DurableApprovalSource.class.getName());

    @Inject
    DataSource dataSource;

    @Inject
    NotificationChannel notificationChannel;

    @Inject
    @ConfigProperty(name = "engine.approval.timeout-hours", defaultValue = "72")
    long timeoutHours;

    @Inject
    @ConfigProperty(name = "engine.approval.poll-seconds", defaultValue = "5")
    long pollSeconds;

    @Inject
    @ConfigProperty(name = "engine.approval.base-url", defaultValue = "http://localhost:8080")
    String baseUrl;

    private final SecureRandom random = new SecureRandom();

    @Override
    public ApprovalResult requestAsync(PermissionRequestId requestId, SessionId sessionId, String action,
                                      String context, String approvalUrl) {
        String token = generateToken();
        String tokenHash = sha256(token);
        Instant now = Instant.now();
        Instant deadline = now.plus(timeoutHours, ChronoUnit.HOURS);
        String url = approvalUrl == null || approvalUrl.isBlank()
                ? (baseUrl + "/api/approval/" + token) : approvalUrl;

        try {
            persistPending(requestId.value(), sessionId.value(), action, context, now, deadline, tokenHash);
        } catch (SQLException e) {
            LOG.warning("approval store unavailable: " + e.getMessage());
            return new ApprovalResult.Denied(Option.some("approval store unavailable"));
        }

        try {
            notificationChannel.send(requestId, sessionId, action, context, url);
        } catch (RuntimeException e) {
            LOG.warning("notification failed: " + e.getMessage());
        }

        return new ApprovalResult.Paused(requestId.value(), url);
    }

    @Override
    public ApprovalResult request(PermissionRequestId requestId, SessionId sessionId,
                                  String action, String context) {
        String token = generateToken();
        String tokenHash = sha256(token);
        Instant now = Instant.now();
        Instant deadline = now.plus(timeoutHours, ChronoUnit.HOURS);
        String approvalUrl = baseUrl + "/api/approval/" + token;

        try {
            persistPending(requestId.value(), sessionId.value(), action, context, now, deadline, tokenHash);
        } catch (SQLException e) {
            LOG.warning("approval store unavailable: " + e.getMessage());
            return new ApprovalResult.Denied(Option.some("approval store unavailable"));
        }

        try {
            notificationChannel.send(requestId, sessionId, action, context, approvalUrl);
        } catch (RuntimeException e) {
            LOG.warning("notification failed: " + e.getMessage());
        }

        // Poll for a decision.
        Instant pollUntil = deadline;
        while (Instant.now().isBefore(pollUntil)) {
            String decision = readDecision(requestId.value());
            if (decision != null) {
                if ("approved".equalsIgnoreCase(decision)) {
                    markDecided(requestId.value());
                    return new ApprovalResult.Approved();
                }
                markDecided(requestId.value());
                String feedback = readFeedback(requestId.value());
                return new ApprovalResult.Denied(Option.some(feedback == null ? "denied" : feedback));
            }
            try {
                Thread.sleep(Math.max(1, pollSeconds) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        markExpired(requestId.value());
        return new ApprovalResult.Denied(Option.some("approval timed out"));
    }

    public boolean resolve(String token, String decision, String feedback) {
        String hash = sha256(token);
        String requestId = findRequestIdByToken(hash);
        if (requestId == null) return false;
        return updateDecision(requestId, decision, feedback);
    }

    public String findApprovalUrl(String token) {
        return baseUrl + "/api/approval/" + token;
    }

    // --- persistence helpers ---

    private void persistPending(String requestId, String sessionId, String action, String context,
                               Instant createdAt, Instant deadline, String tokenHash) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "insert into pending_approval " +
                             "(id, session_id, action, context, created_at, deadline_at, state, token, channel) " +
                             "values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, requestId);
            statement.setString(2, sessionId);
            statement.setString(3, action);
            statement.setString(4, context);
            statement.setTimestamp(5, Timestamp.from(createdAt));
            statement.setTimestamp(6, Timestamp.from(deadline));
            statement.setString(7, "PENDING");
            statement.setString(8, tokenHash);
            statement.setString(9, "email");
            statement.executeUpdate();
        }
    }

    private String readDecision(String requestId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select decision from pending_approval where id = ?")) {
            statement.setString(1, requestId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString(1);
            }
        } catch (SQLException e) {
            LOG.warning("cannot read decision: " + e.getMessage());
            return null;
        }
    }

    private String readFeedback(String requestId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select feedback from pending_approval where id = ?")) {
            statement.setString(1, requestId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString(1);
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public String sessionIdForToken(String token) {
        String hash = sha256(token);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select session_id from pending_approval where token = ?")) {
            statement.setString(1, hash);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString(1);
            }
        } catch (SQLException e) {
            LOG.warning("cannot find session by token: " + e.getMessage());
            return null;
        }
    }

    public String findRequestIdByToken(String tokenHash) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id from pending_approval where token = ?")) {
            statement.setString(1, tokenHash);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString(1);
            }
        } catch (SQLException e) {
            LOG.warning("cannot find request by token: " + e.getMessage());
            return null;
        }
    }

    private boolean updateDecision(String requestId, String decision, String feedback) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update pending_approval set decision = ?, feedback = ?, state = 'DECIDED' " +
                             "where id = ? and state in ('PENDING','ORPHANED')")) {
            statement.setString(1, decision);
            statement.setString(2, feedback);
            statement.setString(3, requestId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            LOG.warning("cannot update decision: " + e.getMessage());
            return false;
        }
    }

    private void markDecided(String requestId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update pending_approval set state = 'DECIDED' where id = ?")) {
            statement.setString(1, requestId);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("cannot mark decided: " + e.getMessage());
        }
    }

    private void markExpired(String requestId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "update pending_approval set state = 'EXPIRED' where id = ?")) {
            statement.setString(1, requestId);
            statement.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("cannot mark expired: " + e.getMessage());
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}