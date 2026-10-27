package de.augmentia.oc.engine.guardrail;

import de.augmentia.oc.engine.http.EngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebhookEgressTest {
    @Test
    void denyWhenHostNotAllowed() {
        EngineConfig.WebhookConfig cfg = new EngineConfig.WebhookConfig(true, "enabled",
                "https://evil.example.com/webhook", 1500, "closed-before-open-after", 65536,
                "full", true, "hmac-sha256", null, List.of("guardrail.example"), List.of(), 60, false, "detect");
        WebhookClient client = new WebhookClient();
        try {
            java.lang.reflect.Field f = WebhookClient.class.getDeclaredField("registry");
            f.setAccessible(true);
            GuardrailRegistry r = new GuardrailRegistry() {
                @Override
                public EngineConfig.WebhookConfig webhook() {
                    return cfg;
                }

                @Override
                public boolean hasWebhook() {
                    return true;
                }
            };
            f.set(client, r);
            java.lang.reflect.Field om = WebhookClient.class.getDeclaredField("mapper");
            om.setAccessible(true);
            om.set(client, new com.fasterxml.jackson.databind.ObjectMapper());
            RuntimeException ex = assertThrows(RuntimeException.class, () -> client.call("tool.before", java.util.Map.of()));
            assertTrue(ex.getMessage().contains("not allowed"));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void allowedIpPermitsEgress() {
        EngineConfig.WebhookConfig cfg = new EngineConfig.WebhookConfig(true, "enabled",
                "http://127.0.0.1:1/webhook", 200, "closed-before-open-after", 65536,
                "full", false, "hmac-sha256", null, List.of(), List.of("127.0.0.1"), 60, false, "detect");
        WebhookClient client = new WebhookClient();
        try {
            java.lang.reflect.Field f = WebhookClient.class.getDeclaredField("registry");
            f.setAccessible(true);
            GuardrailRegistry r = new GuardrailRegistry() {
                @Override
                public EngineConfig.WebhookConfig webhook() { return cfg; }
                @Override
                public boolean hasWebhook() { return true; }
            };
            f.set(client, r);
            java.lang.reflect.Field om = WebhookClient.class.getDeclaredField("mapper");
            om.setAccessible(true);
            om.set(client, new com.fasterxml.jackson.databind.ObjectMapper());
            WebhookClient.WebhookResult res = client.call("tool.before", java.util.Map.of());
            assertTrue(res.shouldBlockBefore());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void disallowedIpBlocksEgress() {
        EngineConfig.WebhookConfig cfg = new EngineConfig.WebhookConfig(true, "enabled",
                "http://127.0.0.1:1/webhook", 200, "closed-before-open-after", 65536,
                "full", false, "hmac-sha256", null, List.of(), List.of("10.0.0.1"), 60, false, "detect");
        WebhookClient client = new WebhookClient();
        try {
            java.lang.reflect.Field f = WebhookClient.class.getDeclaredField("registry");
            f.setAccessible(true);
            GuardrailRegistry r = new GuardrailRegistry() {
                @Override
                public EngineConfig.WebhookConfig webhook() { return cfg; }
                @Override
                public boolean hasWebhook() { return true; }
            };
            f.set(client, r);
            java.lang.reflect.Field om = WebhookClient.class.getDeclaredField("mapper");
            om.setAccessible(true);
            om.set(client, new com.fasterxml.jackson.databind.ObjectMapper());
            assertThrows(RuntimeException.class, () -> client.call("tool.before", java.util.Map.of()));
        } catch (Exception e) {
            fail(e);
        }
    }
}
