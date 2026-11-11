package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.port.store.PendingApprovalStore;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

public final class SqlPendingApprovalStore implements PendingApprovalStore {
    private final DataSource dataSource;

    public SqlPendingApprovalStore(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
    }

    @Override public void persist(String id, String sessionId, String action, String context, Instant createdAt, Instant deadline, String state, String tokenHash, String channel) {
        try { PendingApprovalSql.persistPending(dataSource, id, sessionId, action, context, createdAt, deadline, tokenHash, channel); }
        catch (Exception e) { throw new StoreFailure("cannot persist pending approval", e); }
    }
    @Override public void persistBasic(String id, String sessionId, String action, String context, Instant createdAt, Instant deadline) {
        try { PendingApprovalSql.persistPending(dataSource, id, sessionId, action, context, createdAt, deadline, null, null); }
        catch (Exception e) { throw new StoreFailure("cannot persist pending approval", e); }
    }
    @Override public List<PendingApproval> pending(String sessionId) {
        try { return PendingApprovalSql.pending(dataSource, sessionId); }
        catch (Exception e) { return List.of(); }
    }
    @Override public String decision(String id) { try { return PendingApprovalSql.decision(dataSource, id); } catch (Exception e) { return null; } }
    @Override public String feedback(String id) { try { return PendingApprovalSql.feedback(dataSource, id); } catch (Exception e) { return null; } }
    @Override public String sessionIdForToken(String tokenHash) { try { return PendingApprovalSql.sessionIdForToken(dataSource, tokenHash); } catch (Exception e) { return null; } }
    @Override public String requestIdForToken(String tokenHash) { try { return PendingApprovalSql.requestIdForToken(dataSource, tokenHash); } catch (Exception e) { return null; } }
    @Override public boolean updateDecision(String id, String decision, String feedback) { try { return PendingApprovalSql.updateDecision(dataSource, id, decision, feedback); } catch (Exception e) { return false; } }
    @Override public void markState(String id, String state) { try { PendingApprovalSql.markState(dataSource, id, state); } catch (Exception e) { /* ignored */ } }
    @Override public int recoverOrphans() {
        try { return PendingApprovalSql.recoverOrphans(dataSource); }
        catch (Exception e) { return 0; }
    }
    @Override public int deleteDecidedBefore(Instant cutoff) { try { return PendingApprovalSql.deleteDecidedBefore(dataSource, cutoff); } catch (Exception e) { return 0; } }
}
