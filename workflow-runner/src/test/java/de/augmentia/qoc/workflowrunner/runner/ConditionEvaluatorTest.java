package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {

    private WorkflowContext ctxWith(String key, Object value) {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setState(key, value);
        return ctx;
    }

    // ------------------------------------------------------------------
    // state-based conditions
    // ------------------------------------------------------------------

    @Test
    void stateKeyEquality() {
        WorkflowContext ctx = ctxWith("contract_impact", "internal");
        assertTrue(ConditionEvaluator.matches("state.contract_impact == 'internal'", ctx));
        assertFalse(ConditionEvaluator.matches("state.contract_impact == 'external'", ctx));
    }

    @Test
    void stateKeyInequality() {
        WorkflowContext ctx = ctxWith("traceability_gaps", 0);
        assertTrue(ConditionEvaluator.matches("state.traceability_gaps != '0'", ctx) == false);
        ctx.setState("traceability_gaps", 2);
        assertTrue(ConditionEvaluator.matches("state.traceability_gaps != '0'", ctx));
    }

    @Test
    void stateKeyInequalityUnknownKeyIsTrue() {
        // unknown key -> null -> "!= 'x'" is true (conservative routing)
        assertTrue(ConditionEvaluator.matches("state.unknown != '0'", new WorkflowContext()));
        assertFalse(ConditionEvaluator.matches("state.unknown == '0'", new WorkflowContext()));
    }

    @Test
    void stateBooleanCondition() {
        WorkflowContext ctx = ctxWith("breaking", true);
        assertTrue(ConditionEvaluator.matches("state.breaking == 'true'", ctx));
        assertFalse(ConditionEvaluator.matches("state.breaking == 'false'", ctx));
    }

    @Test
    void stateIntegerCondition() {
        WorkflowContext ctx = ctxWith("criticals", 0);
        assertTrue(ConditionEvaluator.matches("state.criticals == '0'", ctx));
    }

    @Test
    void bareStatusConditionStillSupported() {
        WorkflowContext ctx = ctxWith("lastStatus", "SUCCESS");
        assertTrue(ConditionEvaluator.matches("status == 'SUCCESS'", ctx));
        assertFalse(ConditionEvaluator.matches("status == 'FAILED'", ctx));
    }

    // ------------------------------------------------------------------
    // node output conditions (nodeId + optional field)
    // ------------------------------------------------------------------

    @Test
    void nodeOutputFieldEquality() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("n_stress", Map.of("status", "PASS"));
        assertTrue(ConditionEvaluator.matches("node_n_stress.output.status == 'PASS'", ctx));
        assertFalse(ConditionEvaluator.matches("node_n_stress.output.status == 'FAIL'", ctx));
    }

    @Test
    void nodeOutputWholeEquality() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("n1", "VIP_ORDER");
        assertTrue(ConditionEvaluator.matches("node_n1.output == 'VIP_ORDER'", ctx));
    }

    @Test
    void nodeOutputMissingNodeIsFalse() {
        assertFalse(ConditionEvaluator.matches("node_n_ghost.output.status == 'PASS'", new WorkflowContext()));
    }

    // ------------------------------------------------------------------
    // legacy string conditions unchanged
    // ------------------------------------------------------------------

    @Test
    void legacyContainsStillWorks() {
        assertTrue(ConditionEvaluator.matches("contains:error", "build failed with error"));
        assertFalse(ConditionEvaluator.matches("contains:error", "all green"));
        assertTrue(ConditionEvaluator.matches("equals:GREEN", "green"));
    }

    @Test
    void blankConditionMatchesEverything() {
        assertTrue(ConditionEvaluator.matches(null, "anything"));
        assertTrue(ConditionEvaluator.matches("  ", "anything"));
    }

    @Test
    void unknownOperatorIsFalse() {
        assertFalse(ConditionEvaluator.matches("frobnicate:yes", "anything"));
    }
}
