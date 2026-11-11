package de.augmentia.qoc.workflowrunner.runner;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.augmentia.oc.engine.core.model.EngineResult;
import de.augmentia.oc.engine.core.model.Principal;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.oc.engine.runtime.tool.mcp.McpClient;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.AgentStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;

import java.util.*;

/**
 * Default implementation of NodeDispatcher.
 * Handles all node types: agent, decision, start, hitl, subworkflow, fork, join, loop, end, llm_eval, llm_summarize.
 */
public class DefaultNodeDispatcher implements NodeDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultNodeDispatcher.class);


    private final QocEngine engine;
    private final WorkflowDefinitionStore store;
    private final SubWorkflowExpander expander;
    private final McpServerManager mcpManager;
    private final AgentStore agentStore;

    public DefaultNodeDispatcher(QocEngine engine, WorkflowDefinitionStore store) {
        this(engine, store, null, null);
    }

    public DefaultNodeDispatcher(QocEngine engine, WorkflowDefinitionStore store, McpServerManager mcpManager) {
        this(engine, store, mcpManager, null);
    }

    public DefaultNodeDispatcher(QocEngine engine, WorkflowDefinitionStore store,
                                 McpServerManager mcpManager, AgentStore agentStore) {
        this.engine = engine;
        this.store = store;
        this.expander = store != null ? new SubWorkflowExpander(store) : null;
        this.mcpManager = mcpManager;
        this.agentStore = agentStore;
    }

    @Override
    public NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                              WorkflowContext ctx, String input) {
        LOG.debug("Dispatching node: {} (type: {})", node.id(), node.type());
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

                return runAgent(node, prefixedName, nodeInput, config, ctx);
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
                boolean structuredOutput = node.data().jsonOutput() == null || node.data().jsonOutput();
                QocEngine.AgentConfig config = new QocEngine.AgentConfig(
                        prefixedName, null, 0.3, 4096, 1.0,
                        "You are an evaluation assistant. Respond with valid JSON.",
                        List.of(), List.of(), List.of(), List.of(), structuredOutput,
                        de.augmentia.oc.engine.core.model.Option.none());
                return runAgent(node, prefixedName, prompt, config, ctx);
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
                        List.of(), List.of(), List.of(), List.of(), false,
                        de.augmentia.oc.engine.core.model.Option.none());
                return runAgent(node, prefixedName, prompt, config, ctx);
            }
            case "mcp_call" -> {
                String server = node.data().mcpServer();
                String tool = node.data().mcpTool();
                Map<String, Object> args = node.data().mcpArgs();
                if (args == null) args = Map.of();
                // Resolve template strings in args
                Map<String, Object> resolvedArgs = new HashMap<>();
                for (Map.Entry<String, Object> e : args.entrySet()) {
                    if (e.getValue() instanceof String s) {
                        resolvedArgs.put(e.getKey(), TemplateResolver.resolve(s, ctx));
                    } else {
                        resolvedArgs.put(e.getKey(), e.getValue());
                    }
                }

                if (mcpManager == null) {
                    ctx.setNodeOutput(node.id(), "mcp_call (no mcp manager): " + server + "/" + tool);
                    return new NodeResult("mcp_call (no mcp manager)", null);
                }
                McpClient client = mcpManager.client(server);
                if (client == null) {
                    ctx.setNodeOutput(node.id(), "mcp server not found: " + server);
                    return new NodeResult("mcp server not found: " + server, null);
                }
                de.augmentia.oc.engine.core.json.JsonValue jsonResult = client.call(tool, de.augmentia.oc.engine.core.json.JsonValues.fromJava(resolvedArgs));
                Object result = de.augmentia.oc.engine.core.json.JsonValues.toJava(jsonResult);
                ctx.setNodeOutput(node.id(), result);
                return new NodeResult(jsonResult.toString(), result instanceof Map ? (Map<String, Object>) result : null);
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

    private NodeResult runAgent(WorkflowDefinition.Node node, String prefixedName, String prompt,
                                QocEngine.AgentConfig config, WorkflowContext ctx) {
        // Keep the child session so the parent run journal can link to it.
        SessionId child = engine.createSession(prefixedName, "workflow", "workspace");
        EngineResult result = engine.runPrompt(child, prefixedName, prompt, config, true,
                null, null, Principal.anonymous());
        if (result.failed()) {
            throw new IllegalStateException("Agent node " + node.id() + " failed: "
                    + (result.errorMessage() == null ? "unknown" : result.errorMessage()));
        }
        if (result.paused()) {
            throw new IllegalStateException("Agent node " + node.id()
                    + " is paused and needs approval before the workflow can continue");
        }
        Map<String, Object> structured = StructuredOutputConverter.toMap(result.structured());
        ctx.setNodeOutput(node.id(), structured != null ? structured : result.text());
        return new NodeResult(result.text(), structured, child.value());
    }

    private String prefixedName(WorkflowDefinition.Agent a, String fallback) {
        if (a == null) return "wf:" + (fallback == null ? "unknown" : fallback) + ":1.0.0";
        String name = a.name() == null || a.name().isBlank() ? a.id() : a.name();
        String version = a.version() == null || a.version().isBlank() ? "1.0.0" : a.version();
        return "wf:" + name + ":" + version;
    }

    private QocEngine.AgentConfig toAgentConfig(WorkflowDefinition.Agent a, String prefixedName) {
        de.augmentia.oc.engine.core.model.Option<de.augmentia.oc.engine.core.json.Schema> schemaOpt =
                de.augmentia.oc.engine.core.model.Option.none();
        if (a != null && a.responseSchema() != null && !a.responseSchema().isEmpty()) {
            try {
                de.augmentia.oc.engine.core.json.Result<de.augmentia.oc.engine.core.json.Schema> parsed =
                        de.augmentia.oc.engine.core.json.JsonSchema.parse(
                                de.augmentia.oc.engine.core.json.JsonValues.fromJava(a.responseSchema()));
                if (parsed instanceof de.augmentia.oc.engine.core.json.Result.Ok<de.augmentia.oc.engine.core.json.Schema> ok) {
                    schemaOpt = de.augmentia.oc.engine.core.model.Option.some(ok.value());
                } else if (parsed instanceof de.augmentia.oc.engine.core.json.Result.Err<de.augmentia.oc.engine.core.json.Schema> err) {
                    LOG.warn("Invalid responseSchema for agent {}: {} at {}",
                            a.id(), err.error().message(), err.error().path());
                }
            } catch (RuntimeException e) {
                LOG.warn("Failed to parse responseSchema for agent {}: {}", a.id(), e.getMessage());
            }
        }
        if (a == null) {
            return new QocEngine.AgentConfig(prefixedName, null, 0.3, 4096, 1.0, null,
                    List.of(), List.of(), List.of(), List.of(), false, schemaOpt);
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
            a.jsonOutput(),
            schemaOpt
        );
    }

    private WorkflowDefinition.Agent findAgent(WorkflowDefinition def, String agentRef) {
        if (agentRef == null || agentRef.isBlank()) return null;
        for (WorkflowDefinition.Agent a : def.agents()) {
            if (a.id().equals(agentRef)) return a;
        }
        // Option B: agents are stored globally and referenced by id.
        return agentStore == null ? null : agentStore.get(agentRef);
    }

    private WorkflowDefinition.Node findNode(WorkflowDefinition def, String id) {
        LOG.debug("Finding node: {}", id);
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }
}