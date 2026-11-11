package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;

/**
 * No-op triage service that returns "not retryable" for all errors.
 * Replace with a real LLM-based implementation when ENGINE_TRIAGE_ENABLED=true.
 */
public final class NoOpTriageService implements TriageService {

    @Override
    public TriageResult analyze(TriageContext ctx) {
        return new TriageResult(false, "", "Triage disabled (ENGINE_TRIAGE_ENABLED=false)");
    }
}
