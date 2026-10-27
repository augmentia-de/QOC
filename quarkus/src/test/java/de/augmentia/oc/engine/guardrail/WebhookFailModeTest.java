package de.augmentia.oc.engine.guardrail;

import de.augmentia.oc.engine.http.EngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebhookFailModeTest {

    private WebhookClient client(EngineConfig.WebhookConfig cfg) throws Exception {
        WebhookClient client = new WebhookClient();
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
        return client;
    }

    private EngineConfig.WebhookConfig cfg(String failMode) {
        return new EngineConfig.WebhookConfig(true, "enabled",
                "http://127.0.0.1:1/webhook", 200, failMode, 65536,
                "full", false, "hmac-sha256", null, List.of(), 60, false, "detect");
    }

    @Test
    void closedBeforeOpenAfterBlocksBeforeAndAllowsAfter() throws Exception {
        WebhookClient client = client(cfg("closed-before-open-after"));
        WebhookClient.WebhookResult before = client.call("tool.before", java.util.Map.of());
        assertTrue(before.shouldBlockBefore());
        WebhookClient.WebhookResult after = client.call("tool.after", java.util.Map.of());
        assertEquals("allow", after.decision());
        WebhookClient.WebhookResult perm = client.call("permission.evaluate", java.util.Map.of());
        assertTrue(perm.shouldDenyPermission());
    }

    @Test
    void openAllowsAll() throws Exception {
        WebhookClient client = client(cfg("open"));
        assertDoesNotThrow(() -> client.call("tool.before", java.util.Map.of()));
        WebhookClient.WebhookResult after = client.call("tool.after", java.util.Map.of());
        assertEquals("allow", after.decision());
    }

    @Test
    void closedBlocksAfter() throws Exception {
        WebhookClient client = client(cfg("closed"));
        WebhookClient.WebhookResult before = client.call("tool.before", java.util.Map.of());
        assertTrue(before.shouldBlockBefore());
        WebhookClient.WebhookResult after = client.call("tool.after", java.util.Map.of());
        assertEquals("block", after.decision());
    }

    @Test
    void shadowModeReturnsShadow() throws Exception {
        EngineConfig.WebhookConfig cfg = new EngineConfig.WebhookConfig(true, "shadow",
                "http://127.0.0.1:1/webhook", 200, "closed-before-open-after", 65536,
                "full", false, "hmac-sha256", null, List.of(), 60, false, "detect");
        WebhookClient client = client(cfg);
        WebhookClient.WebhookResult res = client.call("tool.before", java.util.Map.of());
        assertEquals("shadow", res.decision());
    }
}
