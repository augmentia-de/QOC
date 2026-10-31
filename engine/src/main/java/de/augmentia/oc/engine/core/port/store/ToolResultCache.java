package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.IdempotencyKey;

import java.util.Optional;

/**
 * Cache for tool execution results keyed by {@link IdempotencyKey}, so that
 * retries and resumed sessions do not re-execute side-effecting tools.
 *
 * <p>The port uses {@link JsonValue} to stay free of the tool contract layer;
 * the runtime encodes/decodes tool outcomes.</p>
 */
public interface ToolResultCache {

    Optional<JsonValue> find(IdempotencyKey key);

    void store(IdempotencyKey key, String sessionId, String toolName, JsonValue outcome);

    void delete(IdempotencyKey key);

    void deleteBySession(String sessionId);
}