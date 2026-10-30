package de.augmentia.oc.engine.runtime.tool.skill;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;

import java.util.List;
import java.util.Map;

/**
 * Authorizes skill operations with the skill ID as the permission resource.
 *
 * The generic authorizer uses the raw tool input as the resource, which makes
 * rules such as {@code skill:commit} ineffective. This wrapper extracts the
 * ID (or {@code *} for listing) and keeps the rest of the chain unchanged.
 */
public final class SkillAuthorizer implements ToolAuthorizer {

    private final ToolAuthorizer delegate;

    public SkillAuthorizer(ToolAuthorizer delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.delegate = delegate;
    }

    @Override
    public PermissionRequest request(ToolContext context, ToolDefinition definition, ToolCall call) {
        if (definition == null || definition.effectiveName() == null) return delegate.request(context, definition, call);
        String effective = definition.effectiveName();
        if ("skill".equals(effective)) {
            String id = skillId(call == null ? null : call.input());
            PermissionRequest base = delegate.request(context, definition, call);
            if (id == null || id.isBlank()) return base;
            return new PermissionRequest(base.id(), base.sessionId(), "skill", List.of(id),
                    base.save(), base.metadata(), base.source(), base.message());
        }
        if ("skill_list".equals(effective)) {
            PermissionRequest base = delegate.request(context, definition, call);
            return new PermissionRequest(base.id(), base.sessionId(), "skill", List.of("*"),
                    base.save(), base.metadata(), base.source(), base.message());
        }
        return delegate.request(context, definition, call);
    }

    private static String skillId(JsonValue value) {
        if (!(value instanceof JsonValue.Obj obj)) return null;
        JsonValue id = obj.values().get("id");
        return id instanceof JsonValue.Str str ? str.value() : null;
    }
}
