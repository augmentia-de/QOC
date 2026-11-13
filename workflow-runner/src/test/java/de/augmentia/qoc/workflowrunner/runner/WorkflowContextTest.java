package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowContextTest {

    @Test
    void initialPromptSetGet() {
        WorkflowContext ctx = new WorkflowContext();
        assertNull(ctx.getInitialPrompt());
        ctx.setInitialPrompt("phase A");
        assertEquals("phase A", ctx.getInitialPrompt());
        ctx.setInitialPrompt(null);
        assertNull(ctx.getInitialPrompt());
    }

    @Test
    void restoreWithoutInitialPromptKeepsItUntouched() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setInitialPrompt("keep me");
        ctx.restore(Map.of("n1", "out1"), new HashMap<>(Map.of("k", "v")), "n1");
        assertEquals("keep me", ctx.getInitialPrompt());
        assertEquals("out1", ctx.getNodeOutput("n1"));
        assertEquals("n1", ctx.getCurrentNodeId());
    }

    @Test
    void restoreWithInitialPromptOverrides() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.restore(Map.of(), new HashMap<>(), "n1", "restored prompt");
        assertEquals("restored prompt", ctx.getInitialPrompt());
        assertEquals("n1", ctx.getCurrentNodeId());
    }

    @Test
    void restoreWithNullsIsSafe() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.restore(null, null, null, null);
        assertNull(ctx.getInitialPrompt());
        assertNull(ctx.getCurrentNodeId());
    }
}
