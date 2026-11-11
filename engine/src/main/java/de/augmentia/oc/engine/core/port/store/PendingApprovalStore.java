package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.Option;

import java.time.Instant;
import java.util.List;

/**
 * Durable persistence for pending approval requests (web UI and email).
 */
public interface PendingApprovalStore {

    record PendingApproval(String id, String sessionId, String action, String context,
                          Instant createdAt, Instant deadline, String state) {
        public PendingApproval {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (sessionId == null) sessionId = "";
            if (action == null) action = "";
            if (context == null) context = "";
            if (createdAt == null) createdAt = Instant.EPOCH;
            if (deadline == null) deadline = createdAt;
            if (state == null) state = "";
        }
    }

    void persist(String id, String sessionId, String action, String context,
                Instant createdAt, Instant deadline, String state, String tokenHash, String channel);

    void persistBasic(String id, String sessionId, String action, String context,
                     Instant createdAt, Instant deadline);

    List<PendingApproval> pending(String sessionId);

    String decision(String id);

    String feedback(String id);

    String sessionIdForToken(String tokenHash);

    String requestIdForToken(String tokenHash);

    boolean updateDecision(String id, String decision, String feedback);

    void markState(String id, String state);

    int recoverOrphans();

    int deleteDecidedBefore(Instant cutoff);
}
