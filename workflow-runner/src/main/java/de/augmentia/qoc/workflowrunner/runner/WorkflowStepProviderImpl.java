package de.augmentia.qoc.workflowrunner.runner;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.runtime.workflow.WorkflowStepProvider;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements WorkflowStepProvider for the workflow-runner module.
 * Uses getNextStep logic to navigate the React-Flow graph and produce
 * StepResult.Finished with subagentTool calls.
 */
public class WorkflowStepProviderImpl implements WorkflowStepProvider {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowStepProviderImpl.class);


    private final WorkflowDefinition def;
    private final NodeDispatcher dispatcher;

    public WorkflowStepProviderImpl(WorkflowDefinition def, NodeDispatcher dispatcher) {
        this.def = def;
        this.dispatcher = dispatcher;
    }

    @Override
    public StepResult provideStep(int stepNo, Object workflowContext) {
        LOG.debug("provideStep: stepNo={}, currentNode={}", stepNo, ((WorkflowContext) workflowContext).getCurrentNodeId());
        WorkflowContext ctx = (WorkflowContext) workflowContext;

        String nodeId = getNextNodeId(def, ctx);
        if ("DONE".equals(nodeId)) {
            return new StepResult.Finished(
                    FinishReason.NORMAL, TokenCount.zero(), List.of(), List.of());
        }

        WorkflowDefinition.Node node = findNode(def, nodeId);
        if (node == null) {
            return new StepResult.Failed(
                    new de.augmentia.oc.engine.core.contract.provider.FailureKind.InvalidOutput(
                            de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.UNKNOWN),
                    "Node not found: " + nodeId);
        }

        // Execute the node via dispatcher
        NodeResult result = dispatcher.dispatch(node, def, ctx, ctx.getState("lastResult") != null ? ctx.getState("lastResult").toString() : "");
        ctx.setNodeOutput(nodeId, result.structured() != null ? result.structured() : result.text());
        ctx.setState("lastResult", result.text());
        if (result.structured() != null && result.structured().containsKey("status")) {
            ctx.setState("lastStatus", result.structured().get("status"));
        }

        // Create a ModelEvent.TextDelta for the node result
        List<de.augmentia.oc.engine.core.contract.provider.ModelEvent> events = new ArrayList<>();
        events.add(new de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta(result.text(), result.text()));

        // Create tool call outcomes (empty - tools are executed by dispatcher)
        List<StepResult.ToolCallOutcome> outcomes = List.of();

        return new StepResult.Finished(
                FinishReason.NORMAL, TokenCount.zero(), events, outcomes);
    }

    public String getNextNodeId(WorkflowDefinition def, WorkflowContext ctx) {
        LOG.debug("getNextNodeId: currentNode={}", ctx.getCurrentNodeId());
        String currentId = ctx.getCurrentNodeId();

        if (currentId == null) {
            String startNodeId = findStartNode(def);
            ctx.setCurrentNodeId(startNodeId);
            return startNodeId;
        }

        List<WorkflowDefinition.Edge> outgoing = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.source().equals(currentId)) outgoing.add(e);
        }

        if (outgoing.isEmpty()) { LOG.debug("No outgoing edges -> DONE"); return "DONE"; }

        if (outgoing.size() == 1) {
            String target = outgoing.get(0).target();
            ctx.setCurrentNodeId(target);
            return target;
        }

        for (WorkflowDefinition.Edge e : outgoing) {
            if (e.condition() != null && !e.condition().isBlank()
                    && ConditionEvaluator.matches(e.condition(), ctx)) {
                ctx.setCurrentNodeId(e.target());
                return e.target();
            }
        }

        String target = outgoing.get(0).target();
        ctx.setCurrentNodeId(target);
        return target;
    }

    private String findStartNode(WorkflowDefinition def) {
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (WorkflowDefinition.Edge e : def.edges()) targets.add(e.target());
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (!targets.contains(n.id())) return n.id();
        }
        return def.nodes().isEmpty() ? null : def.nodes().get(0).id();
    }

    private WorkflowDefinition.Node findNode(WorkflowDefinition def, String id) {
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }
}