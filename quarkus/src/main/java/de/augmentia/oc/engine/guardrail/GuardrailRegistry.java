package de.augmentia.oc.engine.guardrail;

import de.augmentia.oc.engine.http.EngineConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class GuardrailRegistry {
    @Inject
    EngineConfig config;

    public boolean hasWebhook() {
        return config != null && config.webhookEnabled() && config.webhook() != null && !config.webhook().url().isBlank();
    }

    public EngineConfig.WebhookConfig webhook() {
        if (config == null) return EngineConfig.WebhookConfig.disabled();
        return config.webhook();
    }
}
