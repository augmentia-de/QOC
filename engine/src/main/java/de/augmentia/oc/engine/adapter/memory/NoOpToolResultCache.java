package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.IdempotencyKey;

import java.time.Instant;
import java.util.Optional;

/** No-op tool result cache for minimal (in-memory) mode. */
public final class NoOpToolResultCache implements de.augmentia.oc.engine.core.port.store.ToolResultCache {
    @Override public Optional<JsonValue> find(IdempotencyKey key) { return Optional.empty(); }
    @Override public void store(IdempotencyKey key, String sessionId, String toolName, JsonValue outcome) { /* no-op */ }
    @Override public void delete(IdempotencyKey key) { /* no-op */ }
    @Override public void deleteBySession(String sessionId) { /* no-op */ }
    @Override public int deleteCreatedBefore(Instant cutoff) { return 0; }
}