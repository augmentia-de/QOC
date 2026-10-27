package de.augmentia.oc.engine.guardrail;

import de.augmentia.oc.engine.core.contract.tool.Hooks;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class GuardrailConfigProducer {
    @Produces
    @Singleton
    public Hooks hooks(GuardrailHooks guardrailHooks) {
        return guardrailHooks;
    }
}
