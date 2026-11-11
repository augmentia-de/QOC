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
        return (context, definition, call) -> {
            String message = null;
            if (definition.options().approvalMessage().isPresent()) {
                try {
                    message = definition.options().approvalMessage().orElseThrow().apply(call.input());
                } catch (RuntimeException ignored) {
                    message = null;
                }
            }
            String action = definition.options().permission().orElse(definition.effectiveName());
            java.util.List<String> resources = permissionResources(action, call == null ? null : call.input());
            if (message == null || message.isBlank()) {
                message = action + ": " + (resources.isEmpty() ? "" : resources.get(0));
            }
            if (message.length() > 750) message = message.substring(0, 750) + "…";
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            meta.put("tool", definition.effectiveName());
            meta.put("idempotencyKey", context.idempotencyKey().value());
            meta.put("approvalTier", de.augmentia.oc.engine.core.model.ApprovalTier
                    .forTool(definition.effectiveName()).name());
            meta.put("principalId", context.principal().id());
            meta.put("principalRoles", context.principal().roles());
            return new PermissionRequest(
                    de.augmentia.oc.engine.core.model.PermissionRequestId.create(),
                    context.sessionId(),
                    action,
                    resources.isEmpty() ? java.util.List.of(call.input().toString()) : resources,
                    java.util.List.of(),
                    meta,
                    null,
                    message);
        };
    }

    private static java.util.List<String> permissionResources(String action, de.augmentia.oc.engine.core.json.JsonValue input) {
        if (input instanceof de.augmentia.oc.engine.core.json.JsonValue.Obj obj) {
            de.augmentia.oc.engine.core.json.JsonValue path = obj.values().get("path");
            if (path instanceof de.augmentia.oc.engine.core.json.JsonValue.Str str && !str.value().isBlank()) {
                return java.util.List.of(str.value());
            }
            de.augmentia.oc.engine.core.json.JsonValue pattern = obj.values().get("pattern");
            if (pattern instanceof de.augmentia.oc.engine.core.json.JsonValue.Str str && !str.value().isBlank()) {
                return java.util.List.of(str.value());
            }
            de.augmentia.oc.engine.core.json.JsonValue command = obj.values().get("command");
            if (command instanceof de.augmentia.oc.engine.core.json.JsonValue.Str str && !str.value().isBlank()) {
                return java.util.List.of(str.value());
            }
        }
        return java.util.List.of();
    }
}
