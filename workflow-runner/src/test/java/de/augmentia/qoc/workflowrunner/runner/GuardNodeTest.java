package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardNodeTest {
    private static WorkflowDefinition defWithGuard() {
        WorkflowDefinition.Node guard = new WorkflowDefinition.Node("g", "guard", new WorkflowDefinition.Point(0,0),
                new WorkflowDefinition.NodeData("Guard", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "",
                        Map.of("condition", "score == 'low'", "stateUpdates", Map.of("retry", true),
                                "injectRefs", List.of("artifact:x"), "onTrue", "retryNode"), null));
        return new WorkflowDefinition("wf","wf","1.0","draft", List.of(guard), List.of(), List.of(),"owner","");
    }

    @Test
    void guardSetsStateAndInjectsRefs() {
        WorkflowDefinition def = defWithGuard();
        WorkflowContext ctx = new WorkflowContext();
        ctx.setState("lastStatus", "low");
        // ConditionEvaluator checks lastStatus via status? Our condition uses score; set score via lastResult? Use condition lastStatus? Adjust: condition "lastStatus == 'low'"
        // Rebuild def with condition on lastStatus
        WorkflowDefinition.Node guard = new WorkflowDefinition.Node("g", "guard", new WorkflowDefinition.Point(0,0),
                new WorkflowDefinition.NodeData("Guard", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "",
                        Map.of("condition", "lastStatus == 'low'", "stateUpdates", Map.of("retry", true),
                                "injectRefs", List.of("artifact:x"), "onTrue", "retryNode"), null));
        def = new WorkflowDefinition("wf","wf","1.0","draft", List.of(guard), List.of(), List.of(),"owner","");

        DefaultNodeDispatcher dispatcher = new DefaultNodeDispatcher(null, null, null);
        NodeResult result = dispatcher.dispatch(guard, def, ctx, "");
        assertEquals("true", result.text());
        assertEquals(true, ctx.getState("retry"));
        assertTrue(ctx.getContextInclude().contains("artifact:x"));
        assertEquals("retryNode", ctx.getState("nextNode"));
    }
}
