package de.augmentia.oc.engine.guardrail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.augmentia.oc.engine.http.EngineConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class WebhookClient {
    private static final Logger LOG = Logger.getLogger(WebhookClient.class.getName());
    @Inject
    GuardrailRegistry registry;

    @Inject
    ObjectMapper mapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public WebhookResult call(String eventType, Map<String, Object> payload) {
        EngineConfig.WebhookConfig webhook = registry.webhook();
        if (!registry.hasWebhook() || webhook == null || !webhook.enabled()) {
            return WebhookResult.allow();
        }
        if ("shadow".equalsIgnoreCase(webhook.mode())) {
            return WebhookResult.shadow();
        }
        if (!isAllowedUrl(webhook)) {
            LOG.log(Level.WARNING, "webhook.egress_blocked eventType={0} urlHost={1}", new Object[]{eventType, safeHost(webhook.url())});
            if ("closed-before-open-after".equalsIgnoreCase(webhook.failMode())) {
                throw new RuntimeException("webhook host not allowed");
            }
            return WebhookResult.allow();
        }
        String secret = resolveSecret(webhook.secretRef());
        try {
            String body = mapper.writeValueAsString(payload == null ? Map.of() : payload);
            String eventId = "evt_" + UUID.randomUUID().toString().replace("-", "");
            String timestamp = Instant.now().toString();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(webhook.url()))
                    .timeout(Duration.ofMillis(webhook.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("X-QOC-Event", eventType)
                    .header("X-QOC-Event-Id", eventId)
                    .header("X-QOC-Timestamp", timestamp)
                    .header("X-QOC-Idempotency-Key", "idem_" + eventId);
            if (secret != null && !secret.isBlank() && "hmac-sha256".equalsIgnoreCase(webhook.authType())) {
                String signature = "sha256=" + hmac(secret, timestamp + "." + body);
                builder.header("X-QOC-Signature", signature);
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.log(Level.FINE, "webhook.call eventType={0} status={1}", new Object[]{eventType, response.statusCode()});
            if (response.statusCode() != 200) {
                return applyFailMode(webhook.failMode(), eventType, WebhookResult.allow());
            }
            JsonNode node = mapper.readTree(response.body());
            return parseResponse(node, eventType, webhook.failMode());
        } catch (Exception e) {
            return applyFailMode(webhook.failMode(), eventType, WebhookResult.allow());
        }
    }

    private WebhookResult parseResponse(JsonNode node, String eventType, String failMode) {
        if (node == null || !node.isObject()) {
            return applyFailMode(failMode, eventType, WebhookResult.allow());
        }
        String decision = node.path("decision").asText("allow");
        if ("block".equals(decision) || "deny".equals(decision)) {
            return new WebhookResult(decision, node.path("reason").asText(null),
                    node.path("input"), node.path("content"), node.path("metadata"), node.path("effect").asText(null));
        }
        if ("ask".equals(decision)) {
            return new WebhookResult("ask", node.path("reason").asText(null),
                    node.path("input"), node.path("content"), node.path("metadata"), node.path("effect").asText("ask"));
        }
        if ("redact".equals(decision) || "replace".equals(decision)) {
            return new WebhookResult(decision, node.path("reason").asText(null),
                    node.path("input"), node.path("content"), node.path("metadata"), node.path("effect").asText(null));
        }
        if ("rewrite".equals(decision)) {
            return new WebhookResult(decision, node.path("reason").asText(null),
                    node.path("input"), node.path("content"), node.path("metadata"), node.path("effect").asText(null));
        }
        return WebhookResult.allow();
    }

    private WebhookResult applyFailMode(String failMode, String eventType, WebhookResult fallback) {
        if ("open".equalsIgnoreCase(failMode)) {
            return WebhookResult.allow();
        }
        if ("shadow".equalsIgnoreCase(failMode)) {
            return WebhookResult.shadow();
        }
        if ("closed".equalsIgnoreCase(failMode)) {
            if ("tool.after".equals(eventType)) {
                return WebhookResult.blockAfter();
            }
            if ("permission.evaluate".equals(eventType)) {
                return new WebhookResult("deny", "webhook failed", null, null, null, "deny");
            }
            return new WebhookResult("block", "webhook failed", null, null, null, null);
        }
        // closed-before-open-after (default)
        if ("tool.before".equals(eventType)) {
            return new WebhookResult("block", "webhook failed", null, null, null, null);
        }
        if ("permission.evaluate".equals(eventType)) {
            return new WebhookResult("deny", "webhook failed", null, null, null, "deny");
        }
        if ("tool.after".equals(eventType)) {
            return WebhookResult.allow();
        }
        return fallback;
    }

    private String resolveSecret(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return System.getenv("QOC_WEBHOOK_SECRET");
        }
        String fromEnv = System.getenv(secretRef);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String normalized = secretRef.toUpperCase().replace('-', '_');
        fromEnv = System.getenv(normalized);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return System.getenv("QOC_WEBHOOK_SECRET");
    }

    private String safeHost(String url) {
        try {
            if (url == null) return null;
            return URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAllowedUrl(EngineConfig.WebhookConfig webhook) {
        try {
            if (webhook.allowedHosts() == null || webhook.allowedHosts().isEmpty()) {
                return true;
            }
            URI uri = URI.create(webhook.url());
            String host = uri.getHost();
            if (host == null) return false;
            if (webhook.tlsRequired() && !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            for (String allowed : webhook.allowedHosts()) {
                if (allowed == null || allowed.isBlank()) continue;
                String a = allowed.trim().toLowerCase();
                String h = host.toLowerCase();
                if (h.equals(a) || h.endsWith("." + a)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public record WebhookResult(String decision, String reason, JsonNode input, JsonNode content,
                                JsonNode metadata, String effect) {
        public static WebhookResult allow() {
            return new WebhookResult("allow", null, null, null, null, null);
        }

        public static WebhookResult shadow() {
            return new WebhookResult("shadow", null, null, null, null, null);
        }

        public static WebhookResult blockAfter() {
            return new WebhookResult("block", "webhook failed", null, null, null, null);
        }

        public boolean shouldBlockBefore() {
            return "block".equals(decision);
        }

        public boolean shouldDenyPermission() {
            return "deny".equals(decision);
        }

        public boolean shouldAskPermission() {
            return "ask".equals(decision);
        }

        public boolean shouldRedactAfter() {
            return "redact".equals(decision) || "replace".equals(decision);
        }
    }
}
