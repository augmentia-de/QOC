package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.contract.permission.*;

public interface Hooks {
    ToolCall beforeToolCall(ToolContext context, ToolCall call);

    ToolResult afterToolCall(ToolContext context, ToolResult result);

    default PermissionEvaluation evaluatePermission(PermissionEvaluation evaluation) {
        return evaluation;
    }
}