package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.model.EngineResult;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.WorkflowStore;

import java.util.*;

/**
 * Default implementation of NodeDispatcher.
 * Handles all node types: agent, decision, start, hitl, subworkflow, fork, join, loop, end, llm_eval, llm_summarize.
 */
public class DefaultNodeDispatcher implements NodeDispatcher {

    private final QocEngine engine;
    private final WorkflowStore store;
    private final SubWorkflowExpander expander;

    public DefaultNodeDispatcher(QocEngine engine, WorkflowStore store) {
        this.engine = engine;
        this.store = store;
        this.expander = store != null ? new SubWorkflowExpander(store) : null;
    }

    @Override
    public NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                              WorkflowContext ctx, String input) {
        String type = node.type();
        switch (type) {
            case "agent" -> {
                String agentRef = node.data().agentRef();
                WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);
                String prefixedName = prefixedName(agentDef, agentRef);
                QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName);

                String nodeInput = input;
                List<String> incoming = TopologicalSort.incomingSources(def, node.id());
                if (!incoming.isEmpty() && incoming.size() == 1) {
                    Object predOutput = ctx.getNodeOutput(incoming.get(0));
                    if (predOutput != null && !predOutput.toString().isBlank()) {
                        nodeInput = predOutput.toString();
                    }
                }
                nodeInput = TemplateResolver.resolve(nodeInput, ctx);

                EngineResult result = engine.runPrompt(prefixedName, nodeInput, config);
                Map<String, Object> structured = StructuredOutputConverter.toMap(result.structured());
                ctx.setNodeOutput(node.id(), structured != null ? structured : result.text());
                return new NodeResult(result.text(), structured);
            }
            case "decision" -> {
                List<WorkflowDefinition.Branch> branches = node.data().branches();
                String target = node.data().defaultTarget();
                for (WorkflowDefinition.Branch b : branches) {
                    if (ConditionEvaluator.matches(b.condition(), ctx)) {
                        target = b.target();
                        break;
                    }
                }
                ctx.setNodeOutput(node.id(), "decision -> " + target);
                return new NodeResult("decision -> " + target, Map.of("target", target));
            }
            case "start" -> {
                ctx.setNodeOutput(node.id(), "started");
                return new NodeResult("started", null);
            }
            case "hitl" -> {
                ctx.setNodeOutput(node.id(), "approved");
                return new NodeResult("approved", null);
            }
            case "subworkflow" -> {
                String ref = node.data().workflowRef();
                if (store == null) {
                    ctx.setNodeOutput(node.id(), "subworkflow (no store): " + ref);
                    return new NodeResult("subworkflow (no store): " + ref, null);
                }
                WorkflowDefinition subWf = store.get(ref);
                if (subWf == null) {
                    return new NodeResult("subworkflow not found: " + ref, null);
                }
                WorkflowDefinition expanded = expander.expand(subWf);
                ctx.setNodeOutput(node.id(), "subworkflow:" + ref);
                return new NodeResult("subworkflow:" + ref, null);
            }
            case "fork" -> {
                List<String> targets = TopologicalSort.outgoingTargets(def, node.id());
                Map<String, Object> forkResults = new HashMap<>();
                for (String target : targets) {
                    forkResults.put(target, "forked:" + target);
                }
                ctx.setState("forkResults", forkResults);
                ctx.setNodeOutput(node.id(), forkResults);
                return new NodeResult("forked", forkResults);
            }
            case "join" -> {
                List<String> sources = TopologicalSort.incomingSources(def, node.id());
                Map<String, Object> merged = new HashMap<>();
                for (String src : sources) {
                    Object out = ctx.getNodeOutput(src);
                    if (out != null) merged.put(src, out);
                }
                ctx.setNodeOutput(node.id(), merged);
                return new NodeResult("joined", merged);
            }
            case "loop" -> {
                int maxIter = node.data().maxIterations();
                String exitCond = node.data().exitCondition();
                List<String> body = node.data().bodySteps();
                for (int i = 0; i < maxIter; i++) {
                    for (String bodyNodeId : body) {
                        WorkflowDefinition.Node bodyNode = findNode(def, bodyNodeId);
                        if (bodyNode != null) {
                            dispatch(bodyNode, def, ctx, input);
                        }
                    }
                    if (exitCond != null && !exitCond.isBlank() && ConditionEvaluator.matches(exitCond, ctx)) {
                        break;
                    }
                }
                ctx.setNodeOutput(node.id(), "loop completed");
                return new NodeResult("loop completed", null);
            }
            case "llm_eval" -> {
                String prompt = node.data().title();
                if (prompt == null || prompt.isBlank()) {
                    prompt = "Evaluate: " + ctx.getState("lastResult");
                }
                prompt = TemplateResolver.resolve(prompt, ctx);
                String prefixedName = "wf:llm_eval:" + node.id();
                QocEngine.AgentConfig config = new QocEngine.AgentConfig(
                        prefixedName, null, 0.3, 4096, 1.0,
                        "You are an evaluation assistant. Respond with valid JSON.",
                        List.of(), List.of(), List.of(), List.of(), true);
                EngineResult result = engine.runPrompt(prefixedName, prompt, config);
                Map<String, Object> structured = StructuredOutputConverter.toMap(result.structured());
                ctx.setNodeOutput(node.id(), structured != null ? structured : result.text());
                return new NodeResult(result.text(), structured);
            }
            case "llm_summarize" -> {
                String inputData;
                if (node.data().defaultTarget() != null && !node.data().defaultTarget().isBlank()) {
                    inputData = TemplateResolver.resolve(node.data().defaultTarget(), ctx);
                } else {
                    Object lastRes = ctx.getState("lastResult");
                    inputData = lastRes != null ? lastRes.toString() : "";
                }
                String prompt = "Summarize the following data concisely:\n" + inputData;
                String prefixedName = "wf:llm_summarize:" + node.id();
                QocEngine.AgentConfig config = new QocEngine.AgentConfig(
                        prefixedName, null, 0.3, 4096, 1.0, "You are a summarization assistant.",
                        List.of(), List.of(), List.of(), List.of(), false);
                EngineResult result = engine.runPrompt(prefixedName, prompt, config);
                ctx.setNodeOutput(node.id(), result.text());
                return new NodeResult(result.text(), null);
            }
            case "end" -> {
                ctx.setNodeOutput(node.id(), "done");
                return new NodeResult("done", null);
            }
            default -> {
                ctx.setNodeOutput(node.id(), "skipped (" + type + ")");
                return new NodeResult("skipped (" + type + ")", null);
            }
        }
    }

    private String prefixedName(WorkflowDefinition.Agent a, String fallback) {
        if (a == null) return "wf:" + (fallback == null ? "unknown" : fallback) + ":1.0.0";
        String name = a.name() == null || a.name().isBlank() ? a.id() : a.name();
        String version = a.version() == null || a.version().isBlank() ? "1.0.0" : a.version();
        return "wf:" + name + ":" + version;
    }

    private QocEngine.AgentConfig toAgentConfig(WorkflowDefinition.Agent a, String prefixedName) {
        if (a == null) {
            return new QocEngine.AgentConfig(prefixedName, null, 0.3, 4096, 1.0, null,
                    List.of(), List.of(), List.of(), List.of(), false);
        }
        return new QocEngine.AgentConfig(
            prefixedName,
            a.model(),
            a.temperature(),
            a.maxTokens(),
            a.topP(),
            a.systemPrompt(),
            a.tools(),
            a.guardrailsInput(),
            a.guardrailsOutput(),
            a.hooks(),
            a.jsonOutput()
        );
    }

    private WorkflowDefinition.Agent findAgent(WorkflowDefinition def, String agentRef) {
        if (agentRef == null || agentRef.isBlank()) return null;
        for (WorkflowDefinition.Agent a : def.agents()) {
            if (a.id().equals(agentRef)) return a;
        }
        return null;
    }

    private WorkflowDefinition.Node findNode(WorkflowDefinition def, String id) {
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }
}