package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.model.TokenCount;

import java.time.Duration;

/**
 * Bounds a turn by steps, tool calls, tokens and wall time. It is deliberately
 * a pure function so the engine loop can be tested with a fixed clock.
 */
public record TurnPolicy(int maxSteps, int maxToolCalls, long maxTokens,
                         Duration maxWallTime) {

    public static final TurnPolicy DEFAULT = new TurnPolicy(10, 64, 200_000,
            Duration.ofMinutes(30));

    public TurnPolicy {
        if (maxSteps < 1) throw new IllegalArgumentException("maxSteps must be >= 1");
        if (maxToolCalls < 1) throw new IllegalArgumentException("maxToolCalls must be >= 1");
        if (maxTokens < 1) throw new IllegalArgumentException("maxTokens must be >= 1");
        if (maxWallTime == null || maxWallTime.isNegative() || maxWallTime.isZero()) {
            throw new IllegalArgumentException("maxWallTime must be positive");
        }
    }

    public enum Decision { CONTINUE, ANSWER_NOW, FAIL_TURN }

    public Decision decide(int steps, int toolCalls, TokenCount tokens, Duration elapsed) {
        if (steps >= maxSteps || toolCalls >= maxToolCalls
                || (tokens != null && tokens.total() >= maxTokens)
                || (elapsed != null && elapsed.compareTo(maxWallTime) >= 0)) {
            return Decision.FAIL_TURN;
        }
        // Leave headroom for one more step; the caller may force a final answer.
        if (steps >= maxSteps - 1 || toolCalls >= maxToolCalls - 1) {
            return Decision.ANSWER_NOW;
        }
        return Decision.CONTINUE;
    }
}
