package de.augmentia.oc.engine.core.eval;

import java.util.List;

/**
 * A condition that must hold before the agent may finish.
 * Sealed so all cases are handled exhaustively.
 */
public sealed interface VerifyCondition
        permits VerifyCondition.ToolCalled,
                VerifyCondition.ToolCalledWith,
                VerifyCondition.AnyOf,
                VerifyCondition.AllOf {

    record ToolCalled(String toolName) implements VerifyCondition {}

    record ToolCalledWith(String toolName, String paramPattern) implements VerifyCondition {}

    record AnyOf(List<VerifyCondition> conditions) implements VerifyCondition {}

    record AllOf(List<VerifyCondition> conditions) implements VerifyCondition {}
}
