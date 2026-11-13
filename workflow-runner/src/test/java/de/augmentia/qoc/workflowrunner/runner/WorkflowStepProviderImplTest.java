package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.runtime.step.StepResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WorkflowStepProviderImplTest {

    private static WorkflowDefinition minimalDef() {
        WorkflowDefinition.Node start = new WorkflowDefinition.Node(
                "start", "start", new WorkflowDefinition.Point(0, 0),
                new WorkflowDefinition.NodeData("Start", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        WorkflowDefinition.Node end = new WorkflowDefinition.Node(
                "end", "end", new WorkflowDefinition.Point(10, 0),
                new WorkflowDefinition.NodeData("End", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge("e1", "start", "end", null, null);
        return new WorkflowDefinition("wf", "wf", "1.0.0", "draft",
                List.of(start, end), List.of(edge), List.of(), "owner", "");
    }

    @Test
    void doneReturnsWorkflowDone() {
        WorkflowDefinition def = minimalDef();
        WorkflowContext ctx = new WorkflowContext();
        ctx.setCurrentNodeId("end");
        WorkflowStepProviderImpl provider = new WorkflowStepProviderImpl(def, null, WorkflowEventSink.none());
        // Node "end" has no outgoing edge -> DONE
        StepResult result = provider.provideStep(1, ctx);
        assertInstanceOf(StepResult.WorkflowDone.class, result);
    }

    @Test
    void nodeResultPausedReturnsPaused() {
        // Use a dispatcher that returns PAUSED.
        WorkflowDefinition def = minimalDef();
        WorkflowContext ctx = new WorkflowContext(); // currentNodeId null -> start
        NodeDispatcher dispatcher = (node, d, c, input) -> NodeResult.paused("a1", "/approve/a1");
        WorkflowStepProviderImpl provider = new WorkflowStepProviderImpl(def, dispatcher, WorkflowEventSink.none());
        StepResult result = provider.provideStep(1, ctx);
        assertInstanceOf(StepResult.Paused.class, result);
        assertEquals("a1", ((StepResult.Paused) result).approvalId());
    }

    @Test
    void nodeResultCompletedReturnsFinishedWithUsage() {
        WorkflowDefinition def = minimalDef();
        WorkflowContext ctx = new WorkflowContext();
        NodeDispatcher dispatcher = (node, d, c, input) ->
                new NodeResult("ok", Map.of("status", "done"), "child-1", NodeResult.Status.COMPLETED,
                        null, null, TokenCount.of(3, 4));
        WorkflowStepProviderImpl provider = new WorkflowStepProviderImpl(def, dispatcher, WorkflowEventSink.none());
        StepResult result = provider.provideStep(1, ctx);
        assertInstanceOf(StepResult.Finished.class, result);
        assertEquals(3, ((StepResult.Finished) result).usage().input());
        assertEquals(4, ((StepResult.Finished) result).usage().output());
    }
}
