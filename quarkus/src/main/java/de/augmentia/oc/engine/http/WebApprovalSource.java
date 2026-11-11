package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.adapter.sql.PendingApprovalSql;
import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;
import de.augmentia.oc.engine.core.port.store.PendingApprovalStore;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
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
    Instance<DataSource> dataSource;

    @Inject
    @ConfigProperty(name = "engine.storage", defaultValue = "")
    String storageKind;

    private DataSource dataSourceOrNull() {
        if ("memory".equalsIgnoreCase(storageKind)) return null;
        return dataSource.isResolvable() ? dataSource.get() : null;
    }

    @Override
    public ApprovalResult request(PermissionRequestId requestId, SessionId sessionId,
                                  String action, String context) {
        String key = key(sessionId.value(), requestId.value());
        Instant now = Instant.now();
        Instant deadline = now.plusSeconds(TIMEOUT_SECONDS);
        if (dataSourceOrNull() != null) {
            try {
                persistPending(requestId.value(), sessionId.value(), action, context, now, deadline);
            } catch (Exception e) {
                // Fail closed: an approval that cannot be made durable must not be
                // silently approved or left in a process-local limbo.
                return new ApprovalResult.Denied(Option.some("approval store unavailable"));
            }
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
    public List<PendingApprovalStore.PendingApproval> pending(String sessionId) {
        DataSource ds = dataSourceOrNull();
        if (ds == null) return List.of();
        try {
            return PendingApprovalSql.pending(ds, sessionId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void persistPending(String requestId, String sessionId, String action,
                                String context, Instant createdAt, Instant deadline)
            throws Exception {
        DataSource ds = dataSourceOrNull();
        if (ds == null) return;
        PendingApprovalSql.persistPending(ds, requestId, sessionId, action, context,
                createdAt, deadline, null, null);
    }

    private boolean markDecided(String requestId) {
        return updateState(requestId, DECIDED);
    }

    private void markExpired(String requestId) {
        updateState(requestId, EXPIRED);
    }

    /** Deletes decided approvals older than the cutoff. */
    public int deleteDecidedBefore(Instant cutoff) {
        DataSource ds = dataSourceOrNull();
        if (ds == null) return 0;
        try {
            return PendingApprovalSql.deleteDecidedBefore(ds, cutoff);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Marks approvals from a previous process as orphaned, not silently lost. */
    public int recoverOrphans() {
        DataSource ds = dataSourceOrNull();
        if (ds == null) return 0;
        try {
            return PendingApprovalSql.recoverOrphans(ds);
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean updateState(String requestId, String state) {
        DataSource ds = dataSourceOrNull();
        if (ds == null) return false;
        try {
            PendingApprovalSql.markState(ds, requestId, state);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String key(String sessionId, String requestId) {
        return sessionId + ":" + requestId;
    }

}
