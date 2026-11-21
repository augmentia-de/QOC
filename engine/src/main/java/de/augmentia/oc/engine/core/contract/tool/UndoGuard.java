package de.augmentia.oc.engine.core.contract.tool;

import de.augmentia.oc.engine.core.json.JsonValue;

import java.util.Map;

/**
 * Decides whether a completed forward call may be undone. The default guard
 * allows every call; guards are used when an inverse only exists for a subset
 * of inputs (for example, only when the forward call created a resource).
 */
@FunctionalInterface
public interface UndoGuard {

    boolean allow(JsonValue originalInput, JsonValue originalOutput, Map<String, Object> metadata);
}
