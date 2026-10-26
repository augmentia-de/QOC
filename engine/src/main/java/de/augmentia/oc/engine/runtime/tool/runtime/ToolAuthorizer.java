package de.augmentia.oc.engine.runtime.tool.runtime;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;

/** Builds the permission request for one tool call. */
@FunctionalInterface
public interface ToolAuthorizer {

    PermissionRequest request(ToolContext context, ToolDefinition definition, ToolCall call);

    static ToolAuthorizer defaultAuthorizer() {
        return (context, definition, call) -> new PermissionRequest(
                de.augmentia.oc.engine.core.model.PermissionRequestId.create(),
                context.sessionId(),
                definition.options().permission().orElse(definition.effectiveName()),
                java.util.List.of(call.input().toString()),
                java.util.List.of(),
                java.util.Map.of("tool", definition.effectiveName(),
                        "idempotencyKey", context.idempotencyKey().value()),
                null,
                null);
    }
}
