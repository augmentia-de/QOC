package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Human-in-the-loop approval source for the web UI. {@link #request} blocks
 * until the UI calls {@link #resolve}; a timeout auto-rejects.
 */
@ApplicationScoped
public class WebApprovalSource implements ApprovalSource {

    private static final long TIMEOUT_SECONDS = 300;

    private final Map<String, CompletableFuture<ApprovalResult>> pending = new ConcurrentHashMap<>();

    @Override
    public ApprovalResult request(PermissionRequestId requestId, SessionId sessionId, String action, String context) {
        String key = sessionId.value() + ":" + requestId.value();
        CompletableFuture<ApprovalResult> future = new CompletableFuture<>();
        pending.put(key, future);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.remove(key);
            return new ApprovalResult.Denied(Option.some("approval timed out"));
        }
    }

    /** Resolves a pending request. Returns true if a future was completed. */
    public boolean resolve(String sessionId, String requestId, ApprovalResult result) {
        String key = sessionId + ":" + requestId;
        CompletableFuture<ApprovalResult> future = pending.remove(key);
        if (future == null) return false;
        return future.complete(result);
    }

    public boolean hasPending(String sessionId, String requestId) {
        return pending.containsKey(sessionId + ":" + requestId);
    }
}
