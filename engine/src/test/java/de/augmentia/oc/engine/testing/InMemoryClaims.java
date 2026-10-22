package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ClaimStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory claims; the attempt counter survives release. */
public final class InMemoryClaims implements ClaimStore {

    private final Map<SessionId, Claim> open = new LinkedHashMap<>();
    private final Map<SessionId, Integer> attempts = new LinkedHashMap<>();

    @Override
    public synchronized Option<Claim> acquire(SessionId session, Instant at) {
        if (session == null || at == null) throw new IllegalArgumentException("session and at must not be null");
        if (open.containsKey(session)) {
            return Option.none();
        }
        int attempt = attempts.getOrDefault(session, 0);
        Claim claim = new Claim(session, at, attempt);
        open.put(session, claim);
        attempts.put(session, attempt + 1);
        return Option.some(claim);
    }

    @Override
    public synchronized void release(SessionId session) {
        open.remove(session);
    }

    @Override
    public synchronized List<Claim> open() {
        return List.copyOf(open.values());
    }

    public synchronized int attempts(SessionId session) {
        return attempts.getOrDefault(session, 0);
    }
}
