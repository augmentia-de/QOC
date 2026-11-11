package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.IdempotencyKey;
import de.augmentia.oc.engine.core.port.store.ToolResultCache;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tool result cache: LRU-like map keyed by IdempotencyKey.
 */
public final class InMemoryToolResultCache implements ToolResultCache {

    private final RuntimeState state;

    public InMemoryToolResultCache(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    @Override
    public Optional<JsonValue> find(IdempotencyKey key) {
        if (key == null) return Optional.empty();
        Object v = state.toolCache.get(key);
        if (v instanceof JsonValue) return Optional.of((JsonValue) v);
        return Optional.empty();
    }

    @Override
    public void store(IdempotencyKey key, String sessionId, String toolName, JsonValue outcome) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        state.toolCache.put(key, outcome);
    }

    @Override
    public void delete(IdempotencyKey key) {
        if (key != null) state.toolCache.remove(key);
    }

    @Override
    public void deleteBySession(String sessionId) {
        // Simplified: no session tracking in cache entries
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
        return 0; // In-memory: no timestamps
    }
}
