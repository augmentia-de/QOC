package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link ConditionEvaluator} against a {@link WorkflowContext}.
 *
 * <p>Guards the path-split defect: the field separator was written as
 * {@code split("\\\\.")}, which is the regex {@code \\.} (literal backslash plus
 * any character). The string never split, so every condition on a node field
 * evaluated to {@code false}.</p>
 */
class ConditionEvaluatorContextTest {

    private static WorkflowContext context() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setState("lastStatus", "SUCCESS");
        ctx.setNodeOutput("node_1", Map.of(
                "classification", "VIP_ORDER",
                "name", "Bob",
                "price", 4711L,
                "nested", Map.of("deep", "value")));
        ctx.setNodeOutput("erp-prices", Map.of("total", 2500L));
        return ctx;
    }

    @Test
    void nodeFieldConditionMatches() {
        // Regression: this always returned false before the fix.
        assertTrue(ConditionEvaluator.matches(
                "node_1.output.classification == 'VIP_ORDER'", context()));
    }

    @Test
    void nodeFieldConditionDoesNotMatchOtherValue() {
        assertFalse(ConditionEvaluator.matches(
                "node_1.output.classification == 'STANDARD'", context()));
    }

    @Test
    void nodeFieldConditionHandlesNumbers() {
        assertTrue(ConditionEvaluator.matches("node_1.output.price == '4711'", context()));
    }

    @Test
    void nodeFieldConditionNavigatesNestedPath() {
        assertTrue(ConditionEvaluator.matches(
                "node_1.output.nested.deep == 'value'", context()));
    }

    @Test
    void nodeIdWithHyphenIsSupported() {
        // Editor-generated ids contain hyphens.
        assertTrue(ConditionEvaluator.matches("erp-prices.output.total == '2500'", context()));
    }

    @Test
    void wholeNodeOutputCanBeCompared() {
        assertFalse(ConditionEvaluator.matches("node_1.output == 'anything'", context()),
                "comparing against the whole map must not throw and must not match");
    }

    @Test
    void unknownNodeDoesNotMatch() {
        assertFalse(ConditionEvaluator.matches("node_9.output.x == 'y'", context()));
    }

    @Test
    void lastStatusConditionStillWorks() {
        assertTrue(ConditionEvaluator.matches("status == 'SUCCESS'", context()));
        assertTrue(ConditionEvaluator.matches("lastStatus == 'SUCCESS'", context()));
    }

    @Test
    void legacyStringOperatorsStillWork() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setState("lastResult", "This order has a LOW_MARGIN warning");
        assertTrue(ConditionEvaluator.matches("contains:LOW_MARGIN", ctx));
        assertFalse(ConditionEvaluator.matches("contains:OK", ctx));
    }

    @Test
    void blankConditionIsAlwaysTrue() {
        assertTrue(ConditionEvaluator.matches("", context()));
        assertTrue(ConditionEvaluator.matches(null, context()));
    }
}
