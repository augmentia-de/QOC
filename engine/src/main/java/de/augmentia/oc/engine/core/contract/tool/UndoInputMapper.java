package de.augmentia.oc.engine.core.contract.tool;

import de.augmentia.oc.engine.core.json.JsonValue;

import java.util.Map;

/**
 * Pure mapping from a completed forward tool call to the input of its
 * compensating tool. Implementations must not perform I/O or mutate state.
 */
@FunctionalInterface
public interface UndoInputMapper {

    JsonValue map(JsonValue originalInput, JsonValue originalOutput, Map<String, Object> metadata);
}
