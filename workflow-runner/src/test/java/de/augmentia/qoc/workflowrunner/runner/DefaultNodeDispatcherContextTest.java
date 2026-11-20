package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the package-private helpers of DefaultNodeDispatcher:
 * buildFullContext (Determinismus, Reihenfolge, lastResult-Filter)
 * and resolveSystemPrompt (shared prompt vs. agent fallback).
 */
class DefaultNodeDispatcherContextTest {

    private static WorkflowDefinition def(String systemPrompt) {
        WorkflowDefinition.Node start = new WorkflowDefinition.Node(
                "start", "start", new WorkflowDefinition.Point(0, 0),
                new WorkflowDefinition.NodeData("Start", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        WorkflowDefinition.Node agent = new WorkflowDefinition.Node(
                "n1", "agent", new WorkflowDefinition.Point(10, 0),
                new WorkflowDefinition.NodeData("Agent", "a1", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        WorkflowDefinition.Node end = new WorkflowDefinition.Node(
                "end", "end", new WorkflowDefinition.Point(20, 0),
                new WorkflowDefinition.NodeData("End", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        WorkflowDefinition.Agent a = new WorkflowDefinition.Agent(
                "a1", "Test Agent", "1.0.0", "", "model-x", 0.3, 4096, 1.0,
                List.of(), List.of(), List.of(), List.of(), false, true,
                "agent system prompt", null, "", List.of("*"), "all", List.of(), 10, 1.0, false);
        return new WorkflowDefinition("wf1", "Test", "1.0.0", "draft",
                List.of(start, agent, end), List.of(), List.of(a), "", systemPrompt);
    }

    private static WorkflowContext ctx() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setInitialPrompt("Phase A then B");
        ctx.setNodeOutput("end", "done");
        ctx.setNodeOutput("n1", Map.of("status", "ok"));
        // lastResult in state must not appear twice
        ctx.setState("lastResult", "done");
        ctx.setState("zeta", "z-value");
        ctx.setState("alpha", "a-value");
        return ctx;
    }

    @Test
    void buildFullContextContainsInitialPromptNodeOutputsAndSortedState() {
        WorkflowDefinition d = def("shared prompt");
        String full = DefaultNodeDispatcher.buildFullContext(d, ctx());

        assertTrue(full.contains("## Initial prompt\nPhase A then B"));
        assertTrue(full.contains("### n1 (agent)"));
        assertTrue(full.contains("### end (end)"));
        // Node outputs in def.nodes() order (start missing, as it has no output)
        int idxAgent = full.indexOf("### n1");
        int idxEnd = full.indexOf("### end");
        assertTrue(idxAgent >= 0 && idxEnd > idxAgent, "n1 before end expected");

        // State keys sorted, lastResult filtered
        int idxAlpha = full.indexOf("- alpha:");
        int idxZeta = full.indexOf("- zeta:");
        assertTrue(idxAlpha >= 0 && idxZeta > idxAlpha, "sorted state keys expected");
        assertFalse(full.contains("- lastResult"), "lastResult must not be duplicated");

        // Map-Output als JSON gerendert
        assertTrue(full.contains("\"status\""));
    }

    @Test
    void buildFullContextIsDeterministic() {
        WorkflowDefinition d = def("");
        WorkflowContext c1 = ctx();
        WorkflowContext c2 = ctx();
        assertEquals(DefaultNodeDispatcher.buildFullContext(d, c1),
                DefaultNodeDispatcher.buildFullContext(d, c2));
    }

    @Test
    void buildFullContextWithoutInitialPromptUsesNone() {
        WorkflowDefinition d = def("");
        WorkflowContext c = new WorkflowContext();
        c.setNodeOutput("n1", "x");
        String full = DefaultNodeDispatcher.buildFullContext(d, c);
        assertTrue(full.contains("## Initial prompt\n(none)"));
    }

    @Test
    void resolveSystemPromptPrefersSharedWorkflowPrompt() {
        WorkflowDefinition d = def("shared workflow prompt");
        WorkflowDefinition.Agent a = d.agents().get(0);
        assertEquals("shared workflow prompt", DefaultNodeDispatcher.resolveSystemPrompt(d, a));
    }

    @Test
    void resolveSystemPromptFallsBackToAgentPrompt() {
        WorkflowDefinition d = def("");
        WorkflowDefinition.Agent a = d.agents().get(0);
        assertEquals("agent system prompt", DefaultNodeDispatcher.resolveSystemPrompt(d, a));
    }

    @Test
    void resolveSystemPromptFallsBackToEmptyWithoutAgent() {
        WorkflowDefinition d = def("");
        assertEquals("", DefaultNodeDispatcher.resolveSystemPrompt(d, null));
    }
}
