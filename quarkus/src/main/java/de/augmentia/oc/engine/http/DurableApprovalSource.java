package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.adapter.sql.PendingApprovalSql;
import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;
import de.augmentia.oc.engine.core.port.human.NotificationChannel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.MessageDigest;
import java.security.SecureRandom;
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
    Instance<DataSource> dataSource;

    @Inject
    @ConfigProperty(name = "engine.storage", defaultValue = "")
    String storageKind;

    private DataSource ds() {
        if ("memory".equalsIgnoreCase(storageKind)) return null;
        return dataSource.isResolvable() ? dataSource.get() : null;
    }

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
        if (ds() == null) {
            return new ApprovalResult.Denied(Option.some("approval store unavailable"));
        }
        String token = generateToken();
        String tokenHash = sha256(token);
        Instant now = Instant.now();
        Instant deadline = now.plus(timeoutHours, ChronoUnit.HOURS);
        String url = approvalUrl == null || approvalUrl.isBlank()
                ? (baseUrl + "/api/approval/" + token) : approvalUrl;

        try {
            persistPending(requestId.value(), sessionId.value(), action, context, now, deadline, tokenHash);
        } catch (Exception e) {
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
        if (ds() == null) {
            return new ApprovalResult.Denied(Option.some("approval store unavailable"));
        }
        String token = generateToken();
        String tokenHash = sha256(token);
        Instant now = Instant.now();
        Instant deadline = now.plus(timeoutHours, ChronoUnit.HOURS);
        String approvalUrl = baseUrl + "/api/approval/" + token;

        try {
            persistPending(requestId.value(), sessionId.value(), action, context, now, deadline, tokenHash);
        } catch (Exception e) {
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
                               Instant createdAt, Instant deadline, String tokenHash) throws Exception {
        PendingApprovalSql.persistPending(ds(), requestId, sessionId, action, context,
                createdAt, deadline, tokenHash, "email");
    }

    private String readDecision(String requestId) {
        DataSource ds = ds();
        if (ds == null) return null;
        try {
            return PendingApprovalSql.decision(ds, requestId);
        } catch (Exception e) {
            LOG.warning("cannot read decision: " + e.getMessage());
            return null;
        }
    }

    private String readFeedback(String requestId) {
        DataSource ds = ds();
        if (ds == null) return null;
        try {
            return PendingApprovalSql.feedback(ds, requestId);
        } catch (Exception e) {
            return null;
        }
    }

    public String sessionIdForToken(String token) {
        DataSource ds = ds();
        if (ds == null) return null;
        String hash = sha256(token);
        try {
            return PendingApprovalSql.sessionIdForToken(ds, hash);
        } catch (Exception e) {
            LOG.warning("cannot find session by token: " + e.getMessage());
            return null;
        }
    }

    public String findRequestIdByToken(String tokenHash) {
        DataSource ds = ds();
        if (ds == null) return null;
        try {
            return PendingApprovalSql.requestIdForToken(ds, tokenHash);
        } catch (Exception e) {
            LOG.warning("cannot find request by token: " + e.getMessage());
            return null;
        }
    }

    private boolean updateDecision(String requestId, String decision, String feedback) {
        DataSource ds = ds();
        if (ds == null) return false;
        try {
            return PendingApprovalSql.updateDecision(ds, requestId, decision, feedback);
        } catch (Exception e) {
            LOG.warning("cannot update decision: " + e.getMessage());
            return false;
        }
    }

    private void markDecided(String requestId) {
        DataSource ds = ds();
        if (ds == null) return;
        try {
            PendingApprovalSql.markState(ds, requestId, "DECIDED");
        } catch (Exception e) {
            LOG.warning("cannot mark decided: " + e.getMessage());
        }
    }

    private void markExpired(String requestId) {
        DataSource ds = ds();
        if (ds == null) return;
        try {
            PendingApprovalSql.markState(ds, requestId, "EXPIRED");
        } catch (Exception e) {
            LOG.warning("cannot mark expired: " + e.getMessage());
        }
    }

    /** Deletes decided approvals older than the cutoff. */
    public int deleteDecidedBefore(Instant cutoff) {
        DataSource ds = ds();
        if (ds == null) return 0;
        try {
            return PendingApprovalSql.deleteDecidedBefore(ds, cutoff);
        } catch (Exception e) {
            LOG.warning("cannot purge decided approvals: " + e.getMessage());
            return 0;
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