package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextAssemblerTest {

    private static WorkflowDefinition minimalDef() {
        WorkflowDefinition.Node n1 = new WorkflowDefinition.Node("n1", "agent", new WorkflowDefinition.Point(0,0),
                new WorkflowDefinition.NodeData("Agent", "a", false, "human", 3, "", List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        WorkflowDefinition.Node n2 = new WorkflowDefinition.Node("n2", "agent", new WorkflowDefinition.Point(10,0),
                new WorkflowDefinition.NodeData("Agent2", "a", false, "human", 3, "", List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        return new WorkflowDefinition("wf","wf","1.0","draft", List.of(n1,n2), List.of(), List.of(),"owner","");
    }

    @Test
    void focusedIncludesOnlySelectedRefs() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setInitialPrompt("start");
        ctx.setNodeOutput("n1", "secret output");
        ctx.setNodeOutput("n2", "selected output");
        ctx.includeContext("n2");
        String full = ContextAssembler.assemble(minimalDef(), ctx, ContextAssembler.Policy.FULL, "task", List.of());
        String focused = ContextAssembler.assemble(minimalDef(), ctx, ContextAssembler.Policy.FOCUSED, "task", List.of());
        assertTrue(full.contains("secret output"));
        assertTrue(focused.contains("selected output"));
        assertTrue(!focused.contains("secret output"));
    }

    @Test
    void artifactsModeIncludesOnlyRefs() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.addArtifact(new ArtifactRef("a1", "json", "store:a1", "summary", 10, java.time.Instant.now()));
        String text = ContextAssembler.assemble(minimalDef(), ctx, ContextAssembler.Policy.ARTIFACTS, "task", List.of());
        assertTrue(text.contains("a1"));
        assertTrue(text.contains("summary"));
    }

    @Test
    void focusedIsDeterministic() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setInitialPrompt("start");
        ctx.setNodeOutput("n1", Map.of("b", 2, "a", 1));
        ctx.includeContext("n1");
        String first = ContextAssembler.assemble(minimalDef(), ctx, ContextAssembler.Policy.FOCUSED, "task", List.of());
        String second = ContextAssembler.assemble(minimalDef(), ctx, ContextAssembler.Policy.FOCUSED, "task", List.of());
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
    }
}
