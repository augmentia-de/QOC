package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.model.Option;

/**
 * LLM-based error analysis for self-healing (FR-4.1, FR-4.2, FR-4.3).
 *
 * <p>When a correctable error occurs, the triage service calls a (possibly
 * different, faster) LLM with the stack trace, tool output, and current state,
 * and receives a structured JSON correction suggestion.</p>
 */
public interface TriageService {

    /** Analyzes an error and returns a correction suggestion. */
    TriageResult analyze(TriageContext ctx);

    record TriageContext(
            String toolName,
            String errorMessage,
            String toolInput,
            String state,
            int attempt,
            int maxAttempts) {}

    record TriageResult(
            boolean retryable,
            String correctedInput,
            String explanation) {
        public TriageResult {
            if (correctedInput == null) correctedInput = "";
            if (explanation == null) explanation = "";
        }
    }
}
