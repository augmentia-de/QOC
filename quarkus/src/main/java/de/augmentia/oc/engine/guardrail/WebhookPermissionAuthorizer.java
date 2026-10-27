package de.augmentia.oc.engine.guardrail;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.http.security.Principal;
import de.augmentia.oc.engine.http.security.PrincipalContext;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class WebhookPermissionAuthorizer implements ToolAuthorizer {
    private static final Logger LOG = Logger.getLogger(WebhookPermissionAuthorizer.class.getName());
    @Inject
    WebhookClient webhookClient;

    @Inject
    GuardrailRegistry registry;

    @Inject
    PrincipalContext principalContext;

    @Override
    public PermissionRequest request(ToolContext context, ToolDefinition definition, ToolCall call) {
        PermissionRequest base = ToolAuthorizer.defaultAuthorizer().request(context, definition, call);
        if (!registry.hasWebhook() || context == null) {
            return base;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventType", "permission.evaluate");
        payload.put("eventId", "evt_" + java.util.UUID.randomUUID().toString().replace("-", ""));
        payload.put("sessionId", context.sessionId() == null ? null : context.sessionId().value());
        payload.put("agentId", context.agentId() == null ? null : context.agentId().value());
        payload.put("toolName", definition == null ? null : definition.effectiveName());
        payload.put("callId", call == null || call.id() == null ? null : call.id().value());
        payload.put("idempotencyKey", context.idempotencyKey() == null ? null : context.idempotencyKey().value());
        payload.put("action", base.action());
        payload.put("resources", base.resources());
        payload.put("effect", "ask");
        Map<String, Object> metadata = new HashMap<>(base.metadata());
        Principal principal = principalContext == null ? null : principalContext.get();
        if (principal != null) {
            metadata.put("principalId", principal.id());
            metadata.put("principalName", principal.name());
            metadata.put("principalAuthMode", principal.authMode().name());
        }
        payload.put("metadata", metadata);
        try {
            WebhookClient.WebhookResult result = webhookClient.call("permission.evaluate", payload);
            if (result.shouldDenyPermission()) {
                LOG.log(Level.WARNING, "security.webhook_permission_denied tool={0} reason={1}", new Object[]{definition == null ? null : definition.effectiveName(), result.reason()});
                Map<String, Object> deniedMeta = new HashMap<>(base.metadata());
                deniedMeta.put("webhookDecision", "deny");
                if (result.reason() != null) deniedMeta.put("webhookReason", result.reason());
                return new PermissionRequest(base.id(), base.sessionId(),
                        base.action() + ":denied_by_webhook",
                        base.resources(),
                        base.save(),
                        deniedMeta,
                        base.source(),
                        result.reason() == null ? "Denied by webhook guardrail" : result.reason());
            }
            if (result.shouldAskPermission()) {
                LOG.log(Level.FINE, "security.webhook_permission_ask tool={0}", definition == null ? null : definition.effectiveName());
                Map<String, Object> askMeta = new HashMap<>(base.metadata());
                askMeta.put("webhookDecision", "ask");
                if (result.reason() != null) askMeta.put("webhookReason", result.reason());
                return new PermissionRequest(base.id(), base.sessionId(),
                        base.action(),
                        base.resources(),
                        base.save(),
                        askMeta,
                        base.source(),
                        result.reason());
            }
        } catch (Exception ignored) {
        }
        return base;
    }
}
