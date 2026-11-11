package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.core.port.store.SessionReader;
import de.augmentia.oc.engine.core.port.store.SessionStore;
import de.augmentia.oc.engine.core.port.store.SessionWorkspaceStore;
import de.augmentia.oc.engine.core.port.store.ToolResultCache;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.runtime.session.core.SessionRetention;
import de.augmentia.oc.engine.runtime.session.core.SessionService;

import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the periodic retention/cleanup jobs that used to live inside
 * {@link Engine}. Extracted as the first step of H6 to shrink the god-class
 * without changing behaviour.
 */
public final class RetentionService {

    private static final Logger LOG = Logger.getLogger(RetentionService.class.getName());

    private final SessionRetention sessionRetention;
    private final SessionStore sessions;
    private final Journal journal;
    private final de.augmentia.oc.engine.core.port.store.Conversation conversation;
    private final Clock clock;
    private final int sessionRetentionDays;
    private final int auditRetentionDays;
    private final SessionWorkspaceStore sessionWorkspaces;
    private final de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry processRegistry;
    private final Map<SessionId, List<String>> sessionRoles;
    private final Map<SessionId, Thread> running;
    private final String instanceId;
    private final DataSource dataSource;
    private final ToolResultCache toolResultCache;
    private final WebApprovalSource approvalSource;
    private final DurableApprovalSource durableApprovalSource;

    private ScheduledExecutorService retentionExecutor;

    public RetentionService(SessionRetention sessionRetention, SessionStore sessions, Journal journal,
                            de.augmentia.oc.engine.core.port.store.Conversation conversation, Clock clock,
                            int sessionRetentionDays, int auditRetentionDays,
                            SessionWorkspaceStore sessionWorkspaces,
                            de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry processRegistry,
                            Map<SessionId, List<String>> sessionRoles, Map<SessionId, Thread> running,
                            String instanceId,
                            DataSource dataSource, ToolResultCache toolResultCache,
                            WebApprovalSource approvalSource, DurableApprovalSource durableApprovalSource) {
        this.sessionRetention = sessionRetention;
        this.sessions = sessions;
        this.journal = journal;
        this.conversation = conversation;
        this.clock = clock;
        this.sessionRetentionDays = sessionRetentionDays;
        this.auditRetentionDays = auditRetentionDays;
        this.sessionWorkspaces = sessionWorkspaces;
        this.processRegistry = processRegistry;
        this.sessionRoles = sessionRoles;
        this.running = running;
        this.instanceId = instanceId;
        this.dataSource = dataSource;
        this.toolResultCache = toolResultCache;
        this.approvalSource = approvalSource;
        this.durableApprovalSource = durableApprovalSource;
    }

    public void start() {
        if (sessionRetentionDays <= 0) return;
        retentionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qoc-session-retention");
            thread.setDaemon(true);
            return thread;
        });
        retentionExecutor.scheduleWithFixedDelay(this::purgeExpiredSessions, 0, 1, TimeUnit.HOURS);
        retentionExecutor.scheduleWithFixedDelay(this::sweepEphemeralOrphans, 0, 1, TimeUnit.HOURS);
        retentionExecutor.scheduleWithFixedDelay(this::sweepPausedSessions, 0, 1, TimeUnit.HOURS);
        retentionExecutor.scheduleWithFixedDelay(this::purgeAuditLog, 0, 1, TimeUnit.HOURS);
        retentionExecutor.scheduleWithFixedDelay(this::purgeDecidedApprovals, 0, 1, TimeUnit.HOURS);
        retentionExecutor.scheduleWithFixedDelay(this::purgeToolResultCache, 0, 1, TimeUnit.HOURS);
    }

    public void shutdown() {
        if (retentionExecutor != null) retentionExecutor.shutdownNow();
    }

    private void sweepEphemeralOrphans() {
        if (sessionWorkspaces == null || instanceId == null) return;
        try {
            for (SessionId id : sessionWorkspaces.ephemeralOrphans(instanceId)) {
                try { sessionRetention.delete(id); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                sessionRoles.remove(id);
                String root = sessionWorkspaces.workspaceRoot(id);
                if (root != null && !root.isBlank()) {
                    try { Engine.deleteDirectoryRecursively(java.nio.file.Path.of(root)); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                }
                try { sessionWorkspaces.deleteWorkspace(id); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
            }
        } catch (RuntimeException e) {
            LOG.warn("ephemeral orphan sweep failed: " + e.getMessage());
        }
    }

    private void sweepPausedSessions() {
        if (sessionWorkspaces == null) return;
        try {
            for (SessionInfo info : sessions.list()) {
                if (!"suspended".equals(sessionWorkspaces.state(info.id()))) continue;
                if (info.updated().isBefore(clock.now().minus(Duration.ofDays(
                        Math.max(1, sessionRetentionDays))))) {
                    sessionWorkspaces.updateState(info.id(), "expired");
                    sessionWorkspaces.setPausedPrompt(info.id(), null);
                    LOG.infof("expired paused session %s", info.id().value());
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("paused session sweep failed: " + e.getMessage());
        }
    }

    private void purgeExpiredSessions() {
        try {
            int deleted = sessionRetention.purgeExpired(id -> running.containsKey(id));
            Instant emptyCutoff = clock.now().minus(Duration.ofHours(2));
            int emptyDeleted = 0;
            for (SessionInfo info : sessions.list()) {
                if (running.containsKey(info.id())) continue;
                if (!info.updated().isBefore(emptyCutoff)) continue;
                try {
                    if (conversation.messages(info.id(), de.augmentia.oc.engine.core.model.Seq.zero()).isEmpty()
                            && sessionRetention.delete(info.id())) {
                        emptyDeleted++;
                    }
                } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
            }
            deleted += emptyDeleted;
            if (deleted > 0) {
                Set<SessionId> live = sessions.list().stream().map(SessionInfo::id).collect(java.util.stream.Collectors.toSet());
                sessionRoles.keySet().removeIf(id -> !live.contains(id));
                LOG.infof("session retention deleted %d session(s)", deleted);
            }
        } catch (RuntimeException e) {
            LOG.warn("session retention failed: " + e.getMessage(), e);
        }
    }

    private void purgeAuditLog() {
        if (auditRetentionDays <= 0) return;
        try {
            Instant cutoff = clock.now().minus(Duration.ofDays(Math.max(1, auditRetentionDays)));
            journal.deleteOlderThan(cutoff);
            if (dataSource != null) {
                int d1 = de.augmentia.oc.engine.adapter.sql.AuditSql.deleteToolOutput(dataSource, cutoff);
                if (d1 > 0) LOG.infof("audit cleanup: deleted %d tool_output entries", d1);
                int d2 = de.augmentia.oc.engine.adapter.sql.AuditSql.deleteSnapshots(dataSource, cutoff);
                if (d2 > 0) LOG.infof("audit cleanup: deleted %d snapshots", d2);
            }
            LOG.infof("audit cleanup: purged events older than %d days", auditRetentionDays);
        } catch (RuntimeException e) {
            LOG.warn("audit cleanup failed: " + e.getMessage(), e);
        }
    }

    private void purgeDecidedApprovals() {
        Instant cutoff = clock.now().minus(Duration.ofDays(7));
        int deleted = 0;
        if (approvalSource != null) deleted += approvalSource.deleteDecidedBefore(cutoff);
        if (durableApprovalSource != null) deleted += durableApprovalSource.deleteDecidedBefore(cutoff);
        if (deleted > 0) LOG.infof("purged %d decided approvals", deleted);
    }

    private void purgeToolResultCache() {
        if (toolResultCache == null) return;
        Instant cutoff = clock.now().minus(Duration.ofDays(1));
        int deleted = toolResultCache.deleteCreatedBefore(cutoff);
        if (deleted > 0) LOG.infof("purged %d tool result cache entries", deleted);
    }
}
