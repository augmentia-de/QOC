package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.json.*;

@FunctionalInterface
public interface ToolExecutor {
    ToolOutcome execute(JsonValue input, ToolContext context);
}