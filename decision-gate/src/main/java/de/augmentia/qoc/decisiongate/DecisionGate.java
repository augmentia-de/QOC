package de.augmentia.qoc.decisiongate;

/**
 * Pre-turn decision point in front of the agent loop.
 *
 * <p>The gate sees only the user prompt and answers with a
 * {@link TaskGateDecision}. It must not call tools, must not stream text and
 * must always return a usable decision: failures degrade to
 * {@link TaskGateDecision#fallback(String)} instead of blocking the turn.</p>
 */
@FunctionalInterface
public interface DecisionGate {

    /** Decides how much context the given turn needs. */
    TaskGateDecision decide(String userPrompt);

    /** A gate that preserves the current behaviour (no pre-filtering). */
    static DecisionGate noop() {
        return prompt -> TaskGateDecision.fallback("no gate configured");
    }
}
