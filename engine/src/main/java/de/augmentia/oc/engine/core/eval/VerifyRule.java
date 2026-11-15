package de.augmentia.oc.engine.core.eval;

import java.util.List;

/**
 * A single Verify-on-Stop rule: a condition that must hold before the agent
 * may finish, plus the action to take when it does not.
 */
public record VerifyRule(String name, VerifyCondition condition, VerifyAction onFail) {
    public VerifyRule {
        if (name == null || name.isBlank()) name = "unnamed";
        if (condition == null) throw new IllegalArgumentException("condition must not be null");
        if (onFail == null) onFail = VerifyAction.CONTINUE;
    }
}
