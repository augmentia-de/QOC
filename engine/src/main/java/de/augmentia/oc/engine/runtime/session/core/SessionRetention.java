package de.augmentia.oc.engine.runtime.session.core;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.core.port.store.SessionStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;

/**
 * Deletes sessions and their event history. Retention is based on the last
 * session update ({@link SessionInfo#updated()}); a non-positive retention
 * period disables automatic cleanup.
 */
public final class SessionRetention {

    private final SessionStore sessions;
    private final Journal journal;
    private final Clock clock;
    private final long retentionDays;

    public SessionRetention(SessionStore sessions, Journal journal, Clock clock, long retentionDays) {
        if (sessions == null || journal == null || clock == null) {
            throw new IllegalArgumentException("retention dependencies must not be null");
        }
        this.sessions = sessions;
        this.journal = journal;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /** Deletes one session including its event log. */
    public boolean delete(SessionId session) {
        if (session == null) return false;
        if (sessions.info(session).isEmpty()) return false;
        boolean deleted = sessions.delete(session);
        if (deleted) journal.deleteAggregate(session.value());
        return deleted;
    }

    /**
     * Deletes all sessions whose last update is older than the configured
     * retention. Sessions for which {@code skip} returns true are kept.
     */
    public int purgeExpired(Predicate<SessionId> skip) {
        if (retentionDays <= 0) return 0;
        Instant cutoff = clock.now().minus(Duration.ofDays(retentionDays));
        List<SessionInfo> expired = sessions.list().stream()
                .filter(info -> info.updated().isBefore(cutoff))
                .filter(info -> skip == null || !skip.test(info.id()))
                .toList();
        int deleted = 0;
        for (SessionInfo info : expired) {
            if (delete(info.id())) deleted++;
        }
        return deleted;
    }
}
