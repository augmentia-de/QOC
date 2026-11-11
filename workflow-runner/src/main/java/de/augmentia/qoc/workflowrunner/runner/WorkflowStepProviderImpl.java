package de.augmentia.qoc.workflowrunner.runner;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.runtime.workflow.WorkflowStepProvider;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Implements WorkflowStepProvider for the workflow-runner module.
 * Uses getNextStep logic to navigate the React-Flow graph and produce
 * StepResult.Finished with subagentTool calls.
 *
 * <p>The provider emits {@link WorkflowEventSink} events around every node.
 * It advances {@link WorkflowContext#getCurrentNodeId()} only after a node
 * succeeded, so a retry or restart re-executes exactly the interrupted node.</p>
 */
public class WorkflowStepProviderImpl implements WorkflowStepProvider {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowStepProviderImpl.class);


    private final WorkflowDefinition def;
    private final NodeDispatcher dispatcher;
    private final WorkflowEventSink sink;
    private final Map<String, Integer> attempts = new HashMap<>();

    public WorkflowStepProviderImpl(WorkflowDefinition def, NodeDispatcher dispatcher) {
        this(def, dispatcher, WorkflowEventSink.none());
    }

    public WorkflowStepProviderImpl(WorkflowDefinition def, NodeDispatcher dispatcher,
                                    WorkflowEventSink sink) {
        this.def = def;
        this.dispatcher = dispatcher;
        this.sink = sink == null ? WorkflowEventSink.none() : sink;
    }

    @Override
    public StepResult provideStep(int stepNo, Object workflowContext) {
        LOG.debug("provideStep: stepNo={}, currentNode={}", stepNo, ((WorkflowContext) workflowContext).getCurrentNodeId());
        WorkflowContext ctx = (WorkflowContext) workflowContext;

        String nodeId = getNextNodeId(def, ctx);
        if ("DONE".equals(nodeId) || nodeId == null) {
            return new StepResult.Finished(
                    FinishReason.NORMAL, TokenCount.zero(), List.of(), List.of());
        }

        WorkflowDefinition.Node node = findNode(def, nodeId);
        if (node == null) {
            SessionError error = new SessionError.Unknown("workflow-node", "Node not found: " + nodeId);
            sink.nodeFailed(nodeId, error, 1);
            return new StepResult.Failed(
                    new de.augmentia.oc.engine.core.contract.provider.FailureKind.InvalidOutput(
                            de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.UNKNOWN),
                    "Node not found: " + nodeId);
        }

        int attempt = attempts.merge(nodeId, 1, Integer::sum);
        sink.nodeStarted(nodeId, attempt);

        Map<String, Object> before = new LinkedHashMap<>(ctx.getWorkflowState());
        try {
            NodeResult result = dispatcher.dispatch(node, def, ctx,
                    ctx.getState("lastResult") != null ? ctx.getState("lastResult").toString() : "");
            Object output = result.structured() != null
                    ? WorkflowValues.canonical(result.structured())
                    : result.text();
            ctx.setNodeOutput(nodeId, output);
            ctx.setState("lastResult", result.text());
            if (result.structured() != null && result.structured().containsKey("status")) {
                ctx.setState("lastStatus", result.structured().get("status"));
            }
            ctx.setCurrentNodeId(nodeId);

            Map<String, Object> delta = delta(before, ctx.getWorkflowState());
            String outputText = result.text() == null ? "" : result.text();
            sink.nodeCompleted(nodeId, "COMPLETED", outputText, output, result.sessionId(), delta);
            attempts.remove(nodeId);

            // Create a ModelEvent.TextDelta for the node result
            List<de.augmentia.oc.engine.core.contract.provider.ModelEvent> events = new ArrayList<>();
            events.add(new de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta(outputText, outputText));

            return new StepResult.Finished(
                    FinishReason.NORMAL, TokenCount.zero(), events, List.of());
        } catch (RuntimeException e) {
            SessionError error = new SessionError.Unknown("workflow-node", e.getMessage());
            sink.nodeFailed(nodeId, error, attempt);
            return new StepResult.Failed(
                    new de.augmentia.oc.engine.core.contract.provider.FailureKind.InvalidOutput(
                            de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.UNKNOWN),
                    "Node failed: " + nodeId + ": " + e.getMessage());
        }
    }

    /**
     * Returns the next node without mutating the context. The caller advances
     * the position only after the node succeeded.
     */
    public String getNextNodeId(WorkflowDefinition def, WorkflowContext ctx) {
        LOG.debug("getNextNodeId: currentNode={}", ctx.getCurrentNodeId());
        String currentId = ctx.getCurrentNodeId();

        if (currentId == null) {
            return findStartNode(def);
        }

        List<WorkflowDefinition.Edge> outgoing = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.source().equals(currentId)) outgoing.add(e);
        }

        if (outgoing.isEmpty()) { LOG.debug("No outgoing edges -> DONE"); return "DONE"; }

        if (outgoing.size() == 1) {
            return outgoing.get(0).target();
        }

        for (WorkflowDefinition.Edge e : outgoing) {
            if (e.condition() != null && !e.condition().isBlank()
                    && ConditionEvaluator.matches(e.condition(), ctx)) {
                return e.target();
            }
        }

        return outgoing.get(0).target();
    }

    private Map<String, Object> delta(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> delta = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                delta.put(entry.getKey(), entry.getValue());
            }
        }
        return delta;
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
