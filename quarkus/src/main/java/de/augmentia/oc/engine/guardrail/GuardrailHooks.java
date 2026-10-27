package de.augmentia.oc.engine.guardrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.http.security.Principal;
import de.augmentia.oc.engine.http.security.PrincipalContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GuardrailHooks implements Hooks {
    @Inject
    WebhookClient webhookClient;

    @Inject
    GuardrailRegistry registry;

    @Inject
    PrincipalContext principalContext;

    @Inject
    ObjectMapper mapper;

    @Override
    public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
        if (!registry.hasWebhook() || call == null || context == null) {
            return call;
        }
        Map<String, Object> payload = basePayload(context, call);
        payload.put("eventType", "tool.before");
        payload.put("input", toMap(call.input()));
        WebhookClient.WebhookResult result = webhookClient.call("tool.before", payload);
        if (result.shouldBlockBefore()) {
            throw new RuntimeException(result.reason() == null ? "tool blocked by webhook guardrail" : result.reason());
        }
        if ("rewrite".equals(result.decision()) && result.input() != null) {
            try {
                Object mapped = mapper.treeToValue(result.input(), Object.class);
                if (mapped != null && call.input() != null) {
                    // keep existing input type if possible; rewrite is limited without schema conversion
                }
            } catch (Exception ignored) {
            }
            return call;
        }
        return call;
    }

    @Override
    public ToolResult afterToolCall(ToolContext context, ToolResult result) {
        if (!registry.hasWebhook() || context == null || result == null) {
            return result;
        }
        Map<String, Object> payload = basePayload(context, null);
        payload.put("eventType", "tool.after");
        Map<String, Object> resultMap = new HashMap<>();
        List<Map<String, Object>> content = new ArrayList<>();
        for (var item : result.content()) {
            if (item instanceof ToolContent.TextItem textItem) {
                content.add(Map.of("type", "text", "text", textItem.text()));
            }
        }
        resultMap.put("content", content);
        resultMap.put("metadata", result.metadata());
        Object outputValue = null;
        if (result.output() != null && result.output().isPresent()) {
            outputValue = toMap(result.output().orElse(null));
        }
        resultMap.put("output", outputValue);
        payload.put("result", resultMap);
        WebhookClient.WebhookResult webhookResult = webhookClient.call("tool.after", payload);
        if (webhookResult.shouldRedactAfter() && webhookResult.content() != null && webhookResult.content().isArray()) {
            List<ToolContent> redacted = new ArrayList<>();
            for (var node : webhookResult.content()) {
                if (node.isObject() && "text".equals(node.path("type").asText())) {
                    redacted.add(new ToolContent.TextItem(node.path("text").asText("")));
                }
            }
            if (!redacted.isEmpty()) {
                return new ToolResult(result.output(), redacted, mergeMetadata(result.metadata(), webhookResult.metadata()));
            }
        }
        return result;
    }

    private Map<String, Object> basePayload(ToolContext context, ToolCall call) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", "evt_" + java.util.UUID.randomUUID().toString().replace("-", ""));
        payload.put("sessionId", context.sessionId() == null ? null : context.sessionId().value());
        payload.put("agentId", context.agentId() == null ? null : context.agentId().value());
        if (call != null) {
            payload.put("toolName", call.name());
            payload.put("callId", call.id() == null ? null : call.id().value());
        }
        payload.put("idempotencyKey", context.idempotencyKey() == null ? null : context.idempotencyKey().value());
        Principal principal = principalContext == null ? null : principalContext.get();
        if (principal != null) {
            payload.put("principalId", principal.id());
            payload.put("principalName", principal.name());
        }
        payload.put("timestamp", java.time.Instant.now().toString());
        return payload;
    }

    private Object toMap(Object value) {
        if (value == null) return null;
        try {
            if (value instanceof de.augmentia.oc.engine.core.json.JsonValue jsonValue) {
                return mapper.readValue(de.augmentia.oc.engine.core.json.Json.encode(jsonValue), Object.class);
            }
        } catch (Exception ignored) {
        }
        return value;
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> original, com.fasterxml.jackson.databind.JsonNode metadata) {
        Map<String, Object> merged = new HashMap<>(original == null ? Map.of() : original);
        if (metadata != null && metadata.isObject()) {
            metadata.fields().forEachRemaining(entry -> merged.put(entry.getKey(), toJava(entry.getValue())));
        }
        merged.put("redacted", true);
        return merged;
    }

    private Object toJava(com.fasterxml.jackson.databind.JsonNode node) {
        try {
            return mapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            return node.asText();
        }
    }
}
