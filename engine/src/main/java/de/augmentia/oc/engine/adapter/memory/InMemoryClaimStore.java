package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ClaimStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory claim store: at most one claim per session via putIfAbsent.
 */
public final class InMemoryClaimStore implements ClaimStore {

    private final RuntimeState state;

    public InMemoryClaimStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    @Override
    public Option<Claim> acquire(SessionId session, Instant at) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            if (state.claims.containsKey(session)) return Option.none();
            Claim claim = new Claim(session, at == null ? Instant.EPOCH : at, 0);
            Object prev = state.claims.putIfAbsent(session, claim);
            if (prev != null) return Option.none();
            return Option.some(claim);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void release(SessionId session) {
        if (session == null) return;
        state.claims.remove(session);
    }

    @Override
    public List<Claim> open() {
        List<Claim> result = new ArrayList<>();
        for (Object o : state.claims.values()) {
            if (o instanceof Claim) result.add((Claim) o);
        }
        return List.copyOf(result);
    }
}
