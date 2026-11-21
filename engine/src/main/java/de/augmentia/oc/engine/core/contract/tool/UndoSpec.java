package de.augmentia.oc.engine.core.contract.tool;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.ToolName;

import java.util.Map;

/**
 * Declares the compensating action for a forward tool (Saga pattern). The
 * compensating tool is resolved by its effective name in the tool registry
 * and is executed through the normal permission gate.
 *
 * <p>The mapper is pure. The guard is optional and defaults to allow-all.</p>
 */
public record UndoSpec(ToolName compensatingToolName, UndoInputMapper mapper, UndoGuard guard) {

    public UndoSpec {
        if (compensatingToolName == null) {
            throw new IllegalArgumentException("compensatingToolName must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (guard == null) {
            guard = (input, output, metadata) -> true;
        }
    }

    public static UndoSpec of(ToolName compensatingToolName, UndoInputMapper mapper) {
        return new UndoSpec(compensatingToolName, mapper, (input, output, metadata) -> true);
    }
}
