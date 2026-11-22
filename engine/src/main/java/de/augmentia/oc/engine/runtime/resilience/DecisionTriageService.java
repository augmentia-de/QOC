package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.model.DecisionRequest;
import de.augmentia.oc.engine.core.model.DecisionResult;
import de.augmentia.oc.engine.core.port.runtime.DecisionModel;

/**
 * TriageService that consults an optional DecisionModel for retry classification
 * and falls back to a delegate (typically {@link LlmTriageService} or
 * {@link NoOpTriageService}) on unavailability or failure (fail-open).
 */
public final class DecisionTriageService implements TriageService {

    private final TriageService delegate;
    private final DecisionModel decisionModel;

    public DecisionTriageService(TriageService delegate, DecisionModel decisionModel) {
        this.delegate = delegate == null ? new NoOpTriageService() : delegate;
        this.decisionModel = decisionModel;
    }

    @Override
    public TriageResult analyze(TriageContext ctx) {
        if (decisionModel != null && decisionModel.isAvailable()) {
            try {
                DecisionResult r = decisionModel.decide(new DecisionRequest(
                        "triage",
                        ctx == null ? "" : (ctx.errorMessage() == null ? "" : ctx.errorMessage()),
                        java.util.List.of(),
                        ctx == null ? "" : (ctx.toolInput() == null ? "" : ctx.toolInput()),
                        java.util.Map.of("toolName", ctx == null ? "" : ctx.toolName(),
                                "attempt", ctx == null ? 0 : ctx.attempt(),
                                "maxAttempts", ctx == null ? 0 : ctx.maxAttempts())));
                String a = r.answer() == null ? "" : r.answer().trim().toUpperCase(java.util.Locale.ROOT);
                boolean retryable = a.equals("YES") || a.equals("TRUE") || a.equals("RETRY")
                        || (!a.isEmpty() && r.confidence() >= 0.5 && !a.equals("NO") && !a.equals("FALSE"));
                return new TriageResult(retryable, ctx == null ? "" : ctx.toolInput(), r.answer());
            } catch (RuntimeException e) {
                // fail-open: fall back to delegate
            }
        }
        return delegate.analyze(ctx);
    }
}
