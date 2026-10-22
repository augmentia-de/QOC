package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.runtime.SessionWake;

import java.util.ArrayList;
import java.util.List;

/** Counts wake calls; used to prove admission-before-execution. */
public final class CountingWake implements SessionWake {

    private final List<SessionId> sessions = new ArrayList<>();

    @Override
    public synchronized void wake(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        sessions.add(session);
    }

    public synchronized int count() {
        return sessions.size();
    }

    public synchronized List<SessionId> sessions() {
        return List.copyOf(sessions);
    }
}
