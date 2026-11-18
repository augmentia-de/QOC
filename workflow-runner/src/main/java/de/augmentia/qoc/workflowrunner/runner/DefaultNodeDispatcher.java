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
import de.augmentia.qoc.workflowrunner.store.AgentStorePort;

import java.nio.file.Path;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowJson;

import java.util.*;

/**
 * Default implementation of NodeDispatcher.
 * Handles all node types: agent, decision, start, hitl, subworkflow, fork, join, loop, end, classify.
 */
public class DefaultNodeDispatcher implements NodeDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultNodeDispatcher.class);


    private final QocEngine engine;
    private final WorkflowDefinitionStore store;
    private final SubWorkflowExpander expander;
    private final McpServerManager mcpManager;
    private final AgentStorePort agentStore;
    private final de.augmentia.oc.engine.core.model.Principal principal;
    private final String cacheKey;

    public DefaultNodeDispatcher(QocEngine engine, WorkflowDefinitionStore store) {
        this(engine, store, null, null, de.augmentia.oc.engine.core.model.Principal.anonymous());
    }

    public DefaultNodeDispatcher(QocEngine engine, WorkflowDefinitionStore store, McpServerManager mcpManager) {
        this(engine, store, mcpManager, null, de.augmentia.oc.engine.core.model.Principal.anonymous());
    }

    public DefaultNodeDispatcher(QocEngine engine, WorkflowDefinitionStore store,
                                 McpServerManager mcpManager, AgentStorePort agentStore) {
        this(engine, store, mcpManager, agentStore, de.augmentia.oc.engine.core.model.Principal.anonymous());
    }

    public DefaultNodeDispatcher(QocEngine engine, WorkflowDefinitionStore store,
                                 McpServerManager mcpManager, AgentStorePort agentStore,
                                 de.augmentia.oc.engine.core.model.Principal principal) {
        this(engine, store, mcpManager, agentStore, principal, null);
    }

    public DefaultNodeDispatcher(QocEngine engine, WorkflowDefinitionStore store,
                                 McpServerManager mcpManager, AgentStorePort agentStore,
                                 de.augmentia.oc.engine.core.model.Principal principal,
                                 String cacheKey) {
        this.engine = engine;
        this.store = store;
        this.expander = store != null ? new SubWorkflowExpander(store) : null;
        this.mcpManager = mcpManager;
        this.agentStore = agentStore;
        this.principal = principal == null ? de.augmentia.oc.engine.core.model.Principal.anonymous() : principal;
        this.cacheKey = cacheKey;
    }

    @Override
    public NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                              WorkflowContext ctx, String input) {
        NodeResult result = dispatchImpl(node, def, ctx, input);
        validateOutputContract(node, result);
        return result;
    }

    private NodeResult dispatchImpl(WorkflowDefinition.Node node, WorkflowDefinition def,
                              WorkflowContext ctx, String input) {
        LOG.debug("Dispatching node: {} (type: {})", node.id(), node.type());
        String type = node.type();
        switch (type) {
            case "agent" -> {
                String agentRef = node.data().agentRef();
                WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);
                if (agentDef != null && !agentAllowed(agentDef, principal)) {
                    return new NodeResult("Agent access denied: " + agentRef, null);
                }
                String prefixedName = prefixedName(agentDef, agentRef);
                QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName);

                // Verify-on-Stop: convert NodeData.verifyOnStop (tool names) to VerifyRules.
                List<String> verifyTools = node.data().verifyOnStop();
                if (verifyTools != null && !verifyTools.isEmpty()) {
                    List<de.augmentia.oc.engine.core.eval.VerifyRule> rules = new java.util.ArrayList<>();
                    for (String t : verifyTools) {
                        if (t == null || t.isBlank()) continue;
                        rules.add(new de.augmentia.oc.engine.core.eval.VerifyRule(
                                t.trim(),
                                new de.augmentia.oc.engine.core.eval.VerifyCondition.ToolCalled(t.trim()),
                                de.augmentia.oc.engine.core.eval.VerifyAction.CONTINUE));
                    }
                    if (!rules.isEmpty()) {
                        config = new QocEngine.AgentConfig(
                                config.name(), config.model(), config.temperature(), config.maxTokens(),
                                config.topP(), config.systemPrompt(), config.tools(),
                                config.guardrailsInput(), config.guardrailsOutput(), config.hooks(),
                                config.jsonOutput(), config.responseSchema(), rules);
                    }
                }

                String nodeInput = input;
                List<String> incoming = TopologicalSort.incomingSources(def, node.id());
                if (!incoming.isEmpty() && incoming.size() == 1) {
                    Object predOutput = ctx.getNodeOutput(incoming.get(0));
                    if (predOutput != null && !predOutput.toString().isBlank()) {
                        nodeInput = predOutput.toString();
                    }
                }
                nodeInput = TemplateResolver.resolveStrict(nodeInput, ctx, nodeIds(def));

                NodeResult agentResult = runAgent(node, prefixedName, nodeInput, config, ctx, principal);
                applyPostGuard(node, ctx, agentResult);
                return agentResult;
            }
            case "context_agent_executor" -> {
                String agentRef = node.data().agentRef();
                WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);
                if (agentDef != null && !agentAllowed(agentDef, principal)) {
                    return new NodeResult("Agent access denied: " + agentRef, null);
                }

                // Own registry name per node so this node's system prompt
                // node is not overridden by an already registered agent config
                // (resolveAgentConfig: first registration wins) is overridden.
                String prefixedName = "wf:ctx:" + node.id();

                String mode = str(opt(node, "contextMode"), "focused");
                ContextAssembler.Policy policy = switch (mode.toLowerCase()) {
                    case "full" -> ContextAssembler.Policy.FULL;
                    case "artifacts" -> ContextAssembler.Policy.ARTIFACTS;
                    case "custom" -> ContextAssembler.Policy.CUSTOM;
                    default -> ContextAssembler.Policy.FOCUSED;
                };
                List<String> customRefs = node.data().contextRefs();
                if (customRefs == null || customRefs.isEmpty()) {
                    customRefs = strList(opt(node, "contextRefs"));
                }
                // Auto-derive contextRefs from graph edges when not explicitly set.
                if (customRefs == null || customRefs.isEmpty()) {
                    customRefs = TopologicalSort.incomingSources(def, node.id());
                }

                String systemPrompt = resolveSystemPrompt(def, agentDef);

                String task = node.data().title();
                if (task == null || task.isBlank()) task = node.data().defaultTarget();
                if (task == null || task.isBlank()) task = "Continue with the next step.";

                String fullContext = ContextAssembler.assemble(def, ctx, policy, task, customRefs);
                String userPrompt = "## Previous context\n" + fullContext
                        + "\n\n## Current task\n" + task;

                QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName, systemPrompt);

                // Pre-flight budget check: hard error instead of silent overrun.
                de.augmentia.oc.engine.core.contract.provider.ModelInfo modelInfo =
                        engine.resolveModelInfo(config);
                ContextBudgetGate.check(node.id(), systemPrompt, userPrompt, agentDef, modelInfo);

                // Kein Runtime-Compaction in diesem Lauf.
                QocEngine.ExecutionOverrides overrides = new QocEngine.ExecutionOverrides(
                        null, null, null, null, null, null, false,
                        cacheKey == null || cacheKey.isBlank()
                                ? de.augmentia.oc.engine.core.model.Option.none()
                                : de.augmentia.oc.engine.core.model.Option.some(cacheKey));

                int estimated = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
                if (estimated > CONTEXT_BUDGET_TOKENS) {
                    LOG.warn("context_agent_executor {}: estimated {} tokens exceed budget {}. "
                            + "Insert a compact step before this node if the run fails.",
                            node.id(), estimated, CONTEXT_BUDGET_TOKENS);
                }

                NodeResult result = runAgent(node, prefixedName, userPrompt, config, ctx, principal, overrides);
                applyPostGuard(node, ctx, result);
                return result;
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
                String passed = (input == null || input.isBlank()) ? "started" : input;
                ctx.setNodeOutput(node.id(), passed);
                return new NodeResult(passed, null);
            }
            case "hitl" -> {
                // HITL pause (SDW-08): suspend the run pending human approval.
                // The approval id is deterministic per run+node so replay and
                // resume converge on the same decision record.
                String approvalId = "hitl:" + (ctx.getCurrentNodeId() == null ? node.id() : ctx.getCurrentNodeId())
                        + ":" + System.identityHashCode(ctx);
                Map<String, Object> state = Map.of(
                        "approval", "pending",
                        "node", node.id(),
                        "title", node.data() != null && node.data().title() != null
                                ? node.data().title() : node.id());
                ctx.setNodeOutput(node.id(), state);
                return NodeResult.paused(approvalId, state);
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
                // Edge-based loop: the counter is incremented on each iteration
                // and passed as state to all following nodes.
                int maxIter = Math.max(1, node.data().maxIterations());
                String counterKey = "loopCounter:" + node.id();
                int counter = 0;
                Object current = ctx.getState(counterKey);
                if (current instanceof Number n) counter = n.intValue();
                counter++;
                ctx.setState(counterKey, counter);
                ctx.setState("loopCounter", counter);

                String startTarget = null;
                String exitTarget = null;
                for (WorkflowDefinition.Edge e : def.edges()) {
                    if (!node.id().equals(e.source())) continue;
                    String handle = e.sourceHandle() == null ? "" : e.sourceHandle();
                    if ("start".equalsIgnoreCase(handle)) {
                        startTarget = e.target();
                    } else if ("out".equalsIgnoreCase(handle) || handle.isBlank()) {
                        if (exitTarget == null) exitTarget = e.target();
                    }
                }
                if (exitTarget == null) {
                    for (WorkflowDefinition.Edge e : def.edges()) {
                        if (node.id().equals(e.source())) { exitTarget = e.target(); break; }
                    }
                }

                boolean canLoop = counter <= maxIter && startTarget != null && !startTarget.isBlank();
                if (canLoop && (node.data().exitCondition() == null || node.data().exitCondition().isBlank()
                        || !ConditionEvaluator.matches(node.data().exitCondition(), ctx))) {
                    ctx.setState("nextNode", startTarget);
                } else if (exitTarget != null && !exitTarget.isBlank()) {
                    ctx.setState("nextNode", exitTarget);
                }

                ctx.setNodeOutput(node.id(), "loop " + counter + "/" + maxIter);
                return new NodeResult("loop " + counter + "/" + maxIter, Map.of("counter", counter));
            }
            case "compact" -> {
                return dispatchCompact(node, def, ctx, principal);
            }
            case "guard" -> {
                Map<String, Object> opts = node.data().mcpArgs() == null ? Map.of() : node.data().mcpArgs();
                String condition = str(opts.get("condition"), "");
                boolean matched = condition.isBlank() || ConditionEvaluator.matches(condition, ctx);

                // Limit correction rounds: after MAX_CORRECTION_ROUNDS, do not re-enter the loop.
                int maxRounds = Math.max(1, node.data().maxIterations());
                String condLower = condition.toLowerCase(java.util.Locale.ROOT);
                if (matched && condLower.contains("low_margin")) {
                    int rounds = 0;
                    Object r = ctx.getState("correctionRounds");
                    if (r instanceof Number n) rounds = n.intValue();
                    if (rounds >= maxRounds) {
                        matched = false;
                    } else {
                        ctx.setState("correctionRounds", rounds + 1);
                    }
                }

                Map<String, Object> updates = mapOpt(opts.get("stateUpdates"));
                updates.forEach(ctx::setState);

                List<String> inject = strList(opts.get("injectRefs"));
                inject.forEach(ctx::includeContext);

                String next = matched
                        ? str(opts.get("onTrue"), node.data().defaultTarget())
                        : str(opts.get("onFalse"), node.data().defaultTarget());
                if (next != null && !next.isBlank()) ctx.setState("nextNode", next);

                ctx.setNodeOutput(node.id(), matched ? "true" : "false");
                return new NodeResult(matched ? "true" : "false", Map.of("matched", matched));
            }
            case "inject" -> {
                Map<String, Object> opts = node.data().mcpArgs() == null ? Map.of() : node.data().mcpArgs();
                List<String> refs = strList(opts.get("injectRefs"));
                refs.forEach(ctx::includeContext);
                ctx.setNodeOutput(node.id(), "injected:" + refs);
                return new NodeResult("injected:" + refs, Map.of("refs", refs));
            }
            case "classify" -> {
                Map<String, Object> opts = node.data().mcpArgs() == null ? Map.of() : node.data().mcpArgs();
                // Input resolution (template-aware).
                String inputTemplate = str(opts.get("input"), "");
                Object resolvedInput;
                if (inputTemplate.isBlank()) {
                    Object lastRes = ctx.getState("lastResult");
                    resolvedInput = lastRes != null ? lastRes : input;
                } else if (inputTemplate.contains("${")) {
                    Object obj = TemplateResolver.resolveToObject(inputTemplate, ctx);
                    resolvedInput = obj != null ? obj : TemplateResolver.resolveStrict(inputTemplate, ctx, nodeIds(def));
                } else {
                    resolvedInput = TemplateResolver.resolveStrict(inputTemplate, ctx, nodeIds(def));
                }

                // Fail mode.
                String failMode = str(opts.get("failMode"), "open").toLowerCase(java.util.Locale.ROOT);
                boolean failClosed = "closed".equals(failMode);

                Map<String, Object> execResult;
                try {
                    execResult = TypeSafeDecisionClient.classify(opts, resolvedInput);
                } catch (RuntimeException e) {
                    LOG.warn("classify node {} failed: {}", node.id(), e.getMessage());
                    if (failClosed) {
                        throw new IllegalStateException("classify node " + node.id() + " failed: " + e.getMessage(), e);
                    }
                    execResult = new java.util.LinkedHashMap<>();
                    execResult.put("error", e.getMessage());
                    execResult.put("lowMargin", false);
                    execResult.put("answers", Map.of());
                }

                // Apply output mapping to workflow state.
                Map<String, Object> stateUpdates = new java.util.LinkedHashMap<>();
                TypeSafeDecisionClient.applyOutputMapping(opts, execResult, stateUpdates);
                stateUpdates.forEach(ctx::setState);
                if (execResult.containsKey("lowMargin") && Boolean.TRUE.equals(execResult.get("lowMargin"))) {
                    ctx.setState("low_margin", true);
                }
                ctx.setState("classifyResult", execResult);
                ctx.setNodeOutput(node.id(), execResult);

                // Summary text.
                StringBuilder sb = new StringBuilder("classify");
                Object answersObj = execResult.get("answers");
                if (answersObj instanceof Map<?, ?> answers && !answers.isEmpty()) {
                    sb.append(": ");
                    List<String> parts = new ArrayList<>();
                    for (Map.Entry<?, ?> ae : answers.entrySet()) {
                        if (!(ae.getValue() instanceof Map<?, ?> am)) continue;
                        Object rawVal = am.get("value");
                        if (rawVal == null) rawVal = am.get("mean");
                        if (rawVal == null) rawVal = "?";
                        String val = String.valueOf(rawVal);
                        Object prob = am.get("probability");
                        Object margin = am.get("margin");
                        StringBuilder p = new StringBuilder(String.valueOf(ae.getKey())).append("=").append(val);
                        if (prob != null) p.append(" (").append(prob).append(")");
                        if (margin != null) p.append(" m=").append(margin);
                        parts.add(p.toString());
                    }
                    sb.append(String.join(", ", parts));
                } else if (execResult.containsKey("error")) {
                    sb.append(": error ").append(execResult.get("error"));
                }
                String text = sb.toString();
                ctx.setNodeOutput(node.id(), execResult);
                return new NodeResult(text, execResult);
            }
            case "mcp_call" -> {
                String server = node.data().mcpServer();
                String tool = node.data().mcpTool();
                Map<String, Object> args = node.data().mcpArgs();
                if (args == null) args = Map.of();
                // Resolve template strings in args
                Map<String, Object> resolvedArgs = new HashMap<>();
                for (Map.Entry<String, Object> e : args.entrySet()) {
                    if (e.getValue() instanceof String s && s.contains("${")) {
                        // Preserve the original type when the whole value is a single placeholder.
                        Object resolved = TemplateResolver.resolveToObject(s, ctx);
                        resolvedArgs.put(e.getKey(), resolved != null
                                ? resolved
                                : TemplateResolver.resolveStrict(s, ctx, nodeIds(def)));
                    } else {
                        resolvedArgs.put(e.getKey(), e.getValue());
                    }
                }

                // Built-in check tools (SDW-02): mcpServer "builtin" executes
                // locally, deterministic, no MCP transport. Unknown built-in
                // names fail the node deterministically.
                if ("builtin".equals(server)) {
                    CheckTool check = CheckTools.byName(tool);
                    if (check == null) {
                        ctx.setNodeOutput(node.id(), Map.of("status", "UNKNOWN_TOOL",
                                "tool", String.valueOf(tool)));
                        return new NodeResult("builtin check tool not found: " + tool,
                                Map.of("status", "UNKNOWN_TOOL", "tool", String.valueOf(tool)));
                    }
                    Object workDirArg = resolvedArgs.getOrDefault("work_dir", ".");
                    Path workDir = Path.of(workDirArg == null ? "." : String.valueOf(workDirArg))
                            .toAbsolutePath().normalize();
                    Map<String, Object> result = check.execute(workDir, resolvedArgs, ctx);
                    if (result.containsKey("status")) {
                        ctx.setState("check_result", result.get("status"));
                    }
                    ctx.setNodeOutput(node.id(), result);
                    String text = String.valueOf(result.getOrDefault("status", "done"));
                    return new NodeResult(text, result);
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
            case "rest_call" -> {
                Map<String, Object> opts = node.data().mcpArgs() == null ? Map.of() : node.data().mcpArgs();
                String url = str(opts.get("url"), "");
                String method = str(opts.get("method"), "GET").toUpperCase(java.util.Locale.ROOT);
                Map<String, Object> headers = mapOpt(opts.get("headers"));
                Object body = opts.get("body");
                // Template-Resolution
                url = TemplateResolver.resolveStrict(url, ctx, nodeIds(def));
                Map<String, String> resolvedHeaders = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> h : headers.entrySet()) {
                    Object v = h.getValue();
                    resolvedHeaders.put(h.getKey(), v instanceof String str
                            ? TemplateResolver.resolveStrict(str, ctx, nodeIds(def))
                            : String.valueOf(v));
                }
                Object resolvedBody = body;
                if (body instanceof String bstr) resolvedBody = TemplateResolver.resolveStrict(bstr, ctx, nodeIds(def));

                if (url == null || url.isBlank()) {
                    ctx.setNodeOutput(node.id(), "rest_call: no url");
                    return new NodeResult("rest_call: no url", null);
                }
                try {
                    java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                    java.net.http.HttpRequest.Builder rb = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url));
                    for (Map.Entry<String, String> h : resolvedHeaders.entrySet()) {
                        rb.header(h.getKey(), h.getValue());
                    }
                    java.net.http.HttpRequest request;
                    if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                        String bodyStr = resolvedBody == null ? "" : (resolvedBody instanceof String s ? s : toJson(resolvedBody));
                        rb.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(bodyStr));
                        if (!resolvedHeaders.containsKey("Content-Type")) rb.header("Content-Type", "application/json");
                    } else {
                        rb.method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
                    }
                    java.net.http.HttpResponse<String> resp = client.send(rb.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                    Object parsed = tryParseJson(resp.body());
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("status", resp.statusCode());
                    result.put("body", parsed != null ? parsed : resp.body());
                    ctx.setNodeOutput(node.id(), result);
                    return new NodeResult("rest_call " + resp.statusCode(), result);
                } catch (Exception e) {
                    ctx.setNodeOutput(node.id(), "rest_call error: " + e.getMessage());
                    return new NodeResult("rest_call error: " + e.getMessage(), null);
                }
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

    /**
     * Validates a node's result against its declared {@code outputContract}.
     * A missing contract is a no-op (legacy passthrough). A present contract
     * is checked against the structured output (or the text, if no structured
     * output is available). Violations throw {@link ContractViolationException}.
     */
    private static void validateOutputContract(WorkflowDefinition.Node node, NodeResult result) {
        if (node == null || node.data() == null) return;
        Map<String, Object> contract = node.data().outputContract();
        if (contract == null || contract.isEmpty()) return; // no contract -> passthrough
        Object value = null;
        if (result != null) {
            if (result.structured() != null) value = result.structured();
            else value = result.text();
        }
        // If the node produced no output, there is nothing to validate.
        if (value == null) return;
        boolean strict = Boolean.TRUE.equals(node.data().strict());
        try {
            NodeContractValidator.validate(contract, value);
        } catch (ContractViolationException e) {
            if (strict) {
                throw e; // abort workflow
            }
            // non-strict: warn and continue
            LOG.warn("Contract warning for node {}: {} at {}", node.id(), e.getMessage(), e.path());
        }
    }

    /** Node IDs of the definition, for strict template resolution. */
    private static java.util.Set<String> nodeIds(WorkflowDefinition def) {
        if (def == null || def.nodes() == null) return java.util.Set.of();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id() != null && !n.id().isBlank()) ids.add(n.id());
        }
        return ids;
    }

    private NodeResult runAgent(WorkflowDefinition.Node node, String prefixedName, String prompt,
                                QocEngine.AgentConfig config, WorkflowContext ctx,
                                de.augmentia.oc.engine.core.model.Principal principal) {
        return runAgent(node, prefixedName, prompt, config, ctx, principal, null);
    }

    private NodeResult runAgent(WorkflowDefinition.Node node, String prefixedName, String prompt,
                                QocEngine.AgentConfig config, WorkflowContext ctx,
                                de.augmentia.oc.engine.core.model.Principal principal,
                                QocEngine.ExecutionOverrides overrides) {
        if (principal == null) principal = de.augmentia.oc.engine.core.model.Principal.anonymous();
        // Enforce the agent's tool allow-list (empty = no tools, "*" = all).
        if (overrides == null || overrides.toolSnapshot() == null) {
            de.augmentia.oc.engine.core.contract.tool.ToolSnapshot global = engine.globalToolSnapshot();
            de.augmentia.oc.engine.core.contract.tool.ToolSnapshot filtered = filterToolSnapshotForAgent(global, config);
            QocEngine.ExecutionOverrides ov = new QocEngine.ExecutionOverrides(
                    null, null, null, filtered, null, null);
            if (overrides != null) {
                ov = new QocEngine.ExecutionOverrides(
                        overrides.agentProfile(), overrides.modelInfo(), overrides.step(),
                        filtered, overrides.contextBuilder(), overrides.skillSelection(),
                        overrides.compactionEnabled(), overrides.promptCacheKey());
            }
            overrides = ov;
        }
        // Keep the child session so the parent run journal can link to it.
        SessionId child = engine.createSession(prefixedName, principal.id(), "workspace");
        EngineResult result = engine.runPrompt(child, prefixedName, prompt, config, true,
                null, null, principal, overrides);
        if (result.failed()) {
            throw new IllegalStateException("Agent node " + node.id() + " failed: "
                    + (result.errorMessage() == null ? "unknown" : result.errorMessage()));
        }
        if (result.paused()) {
            String approvalId = result.approvalId() == null || result.approvalId().isBlank()
                    ? java.util.UUID.randomUUID().toString() : result.approvalId();
            return NodeResult.paused(approvalId, result.text());
        }
        Map<String, Object> structured = StructuredOutputConverter.toMap(result.structured());
        ctx.setNodeOutput(node.id(), structured != null ? structured : result.text());
        return new NodeResult(result.text(), structured, child.value(), NodeResult.Status.COMPLETED,
                null, null, de.augmentia.oc.engine.core.model.TokenCount.of(
                        result.inputTokens(), result.outputTokens()));
    }

    private void applyPostGuard(WorkflowDefinition.Node node, WorkflowContext ctx, NodeResult result) {
        if (node == null || ctx == null || result == null) return;
        Map<String, Object> guard = mapOpt(node.data() == null || node.data().mcpArgs() == null
                ? null : node.data().mcpArgs().get("postGuard"));
        if (guard.isEmpty()) return;
        if (result.structured() != null && !result.structured().isEmpty()) {
            ctx.setLastStructuredResult(result.structured());
        } else {
            ctx.setLastStructuredResult(Map.of("text", result.text()));
        }
        String condition = str(guard.get("condition"), "");
        if (!condition.isBlank() && !ConditionEvaluator.matches(condition, ctx)) {
            return;
        }
        mapOpt(guard.get("stateUpdates")).forEach(ctx::setState);
        strList(guard.get("injectRefs")).forEach(ctx::includeContext);
        String next = str(guard.get("nextNode"), null);
        if (next != null && !next.isBlank()) ctx.setState("nextNode", next);
    }

    private static Object opt(WorkflowDefinition.Node node, String key) {
        if (node == null || node.data() == null || node.data().mcpArgs() == null) return null;
        return node.data().mcpArgs().get(key);
    }

    private static Map<String, Object> mapOpt(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private String prefixedName(WorkflowDefinition.Agent a, String fallback) {
        if (a == null) return "wf:" + (fallback == null ? "unknown" : fallback) + ":1.0.0";
        String name = a.name() == null || a.name().isBlank() ? a.id() : a.name();
        String version = a.version() == null || a.version().isBlank() ? "1.0.0" : a.version();
        return "wf:" + name + ":" + version;
    }

    /** Overload with system-prompt override (for context_agent_executor). */
    private QocEngine.AgentConfig toAgentConfig(WorkflowDefinition.Agent a, String prefixedName,
                                                String systemPromptOverride) {
        QocEngine.AgentConfig base = toAgentConfig(a, prefixedName);
        return new QocEngine.AgentConfig(
                base.name(), base.model(), base.temperature(), base.maxTokens(),
                base.topP(), systemPromptOverride, base.tools(),
                base.guardrailsInput(), base.guardrailsOutput(), base.hooks(),
                base.jsonOutput(), base.responseSchema());
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

    /** Filters the global tool snapshot by the agent's configured tool allow-list.
     *  null or "*" => all tools; empty list => no tools (only SubmitResult kept). */
    private static de.augmentia.oc.engine.core.contract.tool.ToolSnapshot filterToolSnapshotForAgent(
            de.augmentia.oc.engine.core.contract.tool.ToolSnapshot global,
            QocEngine.AgentConfig config) {
        if (global == null) return de.augmentia.oc.engine.core.contract.tool.ToolSnapshot.empty();
        java.util.List<String> allow = (config == null || config.tools() == null) ? java.util.List.of("*") : config.tools();
        boolean all = allow.isEmpty() ? false : allow.stream().anyMatch(a -> "*".equals(a));
        if (all) return global;
        if (allow.isEmpty()) {
            // no tools: keep only submit_result (structured output) if present
            java.util.Map<de.augmentia.oc.engine.core.model.ToolName, de.augmentia.oc.engine.core.contract.tool.ToolDefinition> filtered = new java.util.LinkedHashMap<>();
            for (de.augmentia.oc.engine.core.contract.tool.ToolDefinition def : global.list()) {
                if ("submit_result".equalsIgnoreCase(def.effectiveName())) filtered.put(def.name(), def);
            }
            return new de.augmentia.oc.engine.core.contract.tool.ToolSnapshot(filtered);
        }
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>(allow);
        java.util.Map<de.augmentia.oc.engine.core.model.ToolName, de.augmentia.oc.engine.core.contract.tool.ToolDefinition> filtered = new java.util.LinkedHashMap<>();
        for (de.augmentia.oc.engine.core.contract.tool.ToolDefinition def : global.list()) {
            String name = def.effectiveName();
            if ("submit_result".equalsIgnoreCase(name) || allowed.stream().anyMatch(a -> a.equalsIgnoreCase(name))) {
                filtered.put(def.name(), def);
            }
        }
        return new de.augmentia.oc.engine.core.contract.tool.ToolSnapshot(filtered);
    }

    /** RBAC: principal may use the agent? '*' in rolesAllowed means everyone. */
    private static boolean agentAllowed(WorkflowDefinition.Agent a, Principal p) {
        if (a == null) return true;
        List<String> allowed = a.rolesAllowed();
        if (allowed == null || allowed.isEmpty()) return false; // nobody
        if (allowed.contains("*")) return true;
        if (p == null) return false;
        if (p.isAdmin()) return true;
        List<String> roles = p.roles();
        if (roles == null) return false;
        for (String r : roles) {
            if (allowed.stream().anyMatch(x -> x.equalsIgnoreCase(r))) return true;
        }
        return false;
    }

    private WorkflowDefinition.Agent findAgent(WorkflowDefinition def, String agentRef) {
        if (agentRef == null || agentRef.isBlank()) return null;
        for (WorkflowDefinition.Agent a : def.agents()) {
            if (a.id().equals(agentRef) || a.name().equalsIgnoreCase(agentRef)) return a;
        }
        // Option B: agents are stored globally and referenced by id/name.
        if (agentStore != null) {
            WorkflowDefinition.Agent stored = agentStore.get(agentRef);
            if (stored != null) return stored;
            // case-insensitive name lookup
            for (WorkflowDefinition.Agent a : agentStore.listAll()) {
                if (a.name().equalsIgnoreCase(agentRef) || a.id().equalsIgnoreCase(agentRef)) return a;
            }
        }
        // Option B fallback: QOC agent not in store -> convert on the fly.
        if (engine != null) {
            try {
                de.augmentia.oc.engine.runtime.callback.AgentResolver resolver = engine.agentResolver();
                de.augmentia.oc.engine.runtime.QocEngine.AgentConfig cfg = resolver == null ? null : resolver.config(agentRef);
                if (cfg != null) {
                    WorkflowDefinition.Agent converted = fromQocRuntime(cfg);
                    if (converted != null && agentStore != null) {
                        try { agentStore.save(converted.id(), converted); } catch (RuntimeException ignored) {}
                    }
                    return converted;
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private static WorkflowDefinition.Agent fromQocRuntime(de.augmentia.oc.engine.runtime.QocEngine.AgentConfig cfg) {
        if (cfg == null) return null;
        String name = cfg.name() == null || cfg.name().isBlank() ? "agent" : cfg.name();
        String id = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        if (id.isBlank()) id = "agent-" + System.currentTimeMillis();
        return new WorkflowDefinition.Agent(
                id, name, "1.0.0", "", cfg.model() == null ? "" : cfg.model(),
                cfg.temperature(), cfg.maxTokens(), cfg.topP(),
                cfg.tools() == null ? java.util.List.of() : java.util.List.copyOf(cfg.tools()),
                cfg.guardrailsInput() == null ? java.util.List.of() : java.util.List.copyOf(cfg.guardrailsInput()),
                cfg.guardrailsOutput() == null ? java.util.List.of() : java.util.List.copyOf(cfg.guardrailsOutput()),
                cfg.hooks() == null ? java.util.List.of() : java.util.List.copyOf(cfg.hooks()),
                cfg.jsonOutput(), true,
                cfg.systemPrompt() == null ? "" : cfg.systemPrompt(),
                null,
                "", java.util.List.of("*"),
                "all", java.util.List.of());
    }

    private WorkflowDefinition.Node findNode(WorkflowDefinition def, String id) {
        LOG.debug("Finding node: {}", id);
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }

    /**
     * Returns the system prompt for a context_agent_executor node:
     * the shared workflow prompt (for prompt caching), if set,
     * otherwise the agent system prompt. The context always resides in the
     * user message, never in the system prompt.
     */
    static String resolveSystemPrompt(WorkflowDefinition def, WorkflowDefinition.Agent agentDef) {
        String shared = def.systemPrompt();
        if (shared != null && !shared.isBlank()) return shared;
        return agentDef == null || agentDef.systemPrompt() == null ? "" : agentDef.systemPrompt();
    }

    /**
     * Builds the full workflow context as prompt text: initial prompt,
     * node outputs in definition order and sorted workflow state.
     * Deterministic so the prompt stays stable across runs (cacheable).
     */
    static String buildFullContext(WorkflowDefinition def, WorkflowContext ctx) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Initial prompt\n");
        String initial = ctx.getInitialPrompt();
        sb.append(initial == null || initial.isBlank() ? "(none)" : initial).append("\n\n");

        sb.append("## Node outputs\n");
        for (WorkflowDefinition.Node n : def.nodes()) {
            Object out = ctx.getNodeOutput(n.id());
            if (out == null) continue;
            sb.append("### ").append(n.id()).append(" (").append(n.type()).append(")\n");
            sb.append(renderValue(out)).append("\n\n");
        }

        sb.append("## Workflow state\n");
        ctx.getWorkflowState().entrySet().stream()
                .filter(e -> !"lastResult".equals(e.getKey())) // already present in node outputs
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                        .append(renderValue(e.getValue())).append('\n'));

        return sb.toString().trim();
    }

    private static String renderValue(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        try {
            return WorkflowJson.mapper().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static final int CHARS_PER_TOKEN = 4;
    private static final int CONTEXT_BUDGET_TOKENS = 96_000;

    /** Rough token estimate; only for the pre-run warning (no compaction). */
    static int estimateTokens(String text) {
        return text == null ? 0 : text.length() / CHARS_PER_TOKEN;
    }

    // ==================== compact node (deterministic context transformer) ====================

    /**
     * Compacts one or more upstream node outputs into a precise, deterministic
     * context block that is stored as this node's output. No LLM is involved
     * unless {@code mode=llm} with an {@code agentRef}.
     *
     * Node data contract:
     * <ul>
     *   <li>{@code defaultTarget}: comma-separated node IDs or {@code ${node.output}}
     *       templates to compact; empty = all available node outputs</li>
     *   <li>{@code mcpArgs} (options bag):
     *     <ul>
     *       <li>{@code mode}: "truncate" (default) | "filter_keys" | "bulletize" | "llm"</li>
     *       <li>{@code maxChars}: hard limit for truncate (default 4000)</li>
     *       <li>{@code keepHead} / {@code keepTail}: chars kept from start/end in truncate</li>
     *       <li>{@code keys}: list of keys to keep in filter_keys</li>
     *       <li>{@code include}, {@code exclude}: literal substring filters (truncate/bulletize)</li>
     *       <li>{@code sectionTitle}: heading of the compact block (default "Compacted context")</li>
     *       <li>{@code prompt}: instruction for mode=llm (falls back to data.title)</li>
     *     </ul>
     *   </li>
     * </ul>
     * The transformation is pure and replayable: same inputs always produce the
     * same output, so journal-replay restores an identical node output.
     */
    private NodeResult dispatchCompact(WorkflowDefinition.Node node, WorkflowDefinition def,
                                       WorkflowContext ctx, Principal principal) {
        Map<String, Object> opts = node.data().mcpArgs() == null ? Map.of() : node.data().mcpArgs();
        String mode = str(opts.get("mode"), "truncate");
        String sectionTitle = str(opts.get("sectionTitle"), "Compacted context");

        // 1) Input selection: explicit sources (defaultTarget) or all node outputs.
        List<String> sources = new ArrayList<>();
        String target = node.data().defaultTarget();
        if (target != null && !target.isBlank()) {
            for (String part : target.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                // Support ${node.output} style references and bare node ids.
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\$\\{([a-zA-Z0-9_]+)\\.output").matcher(t);
                if (m.find()) sources.add(m.group(1));
                else sources.add(t);
            }
        }

        StringBuilder inputs = new StringBuilder();
        if (sources.isEmpty()) {
            for (WorkflowDefinition.Node n : def.nodes()) {
                if (n.id().equals(node.id())) continue;
                Object out = ctx.getNodeOutput(n.id());
                if (out == null) continue;
                inputs.append("### ").append(n.id()).append(" (").append(n.type()).append(")\n")
                        .append(renderValue(out)).append('\n');
            }
        } else {
            for (String src : sources) {
                Object out = ctx.getNodeOutput(src);
                if (out == null) continue;
                inputs.append("### ").append(src).append("\n")
                        .append(renderValue(out)).append('\n');
            }
        }
        String raw = inputs.toString().trim();
        if (raw.isEmpty()) {
            ctx.setNodeOutput(node.id(), sectionTitle + ": (no input)");
            return new NodeResult(sectionTitle + ": (no input)", null);
        }

        // 2) Deterministic transformations.
        String compacted;
        switch (mode) {
            case "filter_keys" -> compacted = filterKeys(raw, opts);
            case "bulletize" -> compacted = bulletize(raw, opts);
            case "llm" -> {
                return dispatchCompactLlm(node, def, raw, opts, ctx, principal);
            }
            default -> compacted = truncate(raw, opts);
        }

        String output = "## " + sectionTitle + "\n" + compacted;
        ctx.setNodeOutput(node.id(), output);
        LOG.debug("compact node {}: mode={} chars={} -> {}", node.id(), mode, raw.length(),
                compacted.length());
        return new NodeResult(output, null);
    }

    /** LLM variant: same deterministic input selection, model-based refinement. */
    private NodeResult dispatchCompactLlm(WorkflowDefinition.Node node, WorkflowDefinition def,
                                          String raw, Map<String, Object> opts,
                                          WorkflowContext ctx, Principal principal) {
        String instruction = str(opts.get("prompt"), node.data().title());
        if (instruction == null || instruction.isBlank()) {
            instruction = "Reduce this context to what is needed for the next step. Keep exact identifiers, paths, commands and results.";
        }
        String maxChars = str(opts.get("maxChars"), "");
        if (!maxChars.isEmpty()) {
            try {
                int limit = Integer.parseInt(maxChars);
                instruction = instruction + "\nLimit the result to about " + limit + " characters.";
            } catch (NumberFormatException ignored) {
            }
        }
        String agentRef = node.data().agentRef();
        WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);
        String prefixedName = prefixedName(agentDef, agentRef);
        QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName);
        String prompt = instruction + "\n\n<content-to-compact>\n" + raw + "\n</content-to-compact>";
        NodeResult result = runAgent(node, prefixedName, prompt, config, ctx, principal);
        String output = "## " + str(opts.get("sectionTitle"), "Compacted context") + "\n" + result.text();
        ctx.setNodeOutput(node.id(), output);
        return new NodeResult(output, result.structured(), result.sessionId());
    }

    /** Hard head/tail truncation with deterministic separator. */
    static String truncate(String raw, Map<String, Object> opts) {
        int maxChars = intOpt(opts.get("maxChars"), 4000);
        if (raw.length() <= maxChars) return raw;
        int keepHead = Math.min(intOpt(opts.get("keepHead"), maxChars / 2), maxChars);
        int keepTail = Math.min(intOpt(opts.get("keepTail"), maxChars - keepHead), maxChars - keepHead);
        keepHead = Math.max(0, keepHead);
        keepTail = Math.max(0, keepTail);
        String head = raw.substring(0, keepHead);
        String tail = raw.substring(raw.length() - keepTail);
        return head + "\n[... " + (raw.length() - keepHead - keepTail) + " chars omitted ...]\n" + tail;
    }

    /** Keeps only lines that contain one of the literal include/exclude filters. */
    static String filterLines(String raw, Map<String, Object> opts) {
        List<String> include = strList(opts.get("include"));
        List<String> exclude = strList(opts.get("exclude"));
        if (include.isEmpty() && exclude.isEmpty()) return raw;
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            boolean keep = true;
            if (!include.isEmpty()) {
                keep = false;
                for (String inc : include) if (line.contains(inc)) { keep = true; break; }
            }
            if (keep && !exclude.isEmpty()) {
                for (String exc : exclude) if (line.contains(exc)) { keep = false; break; }
            }
            if (keep) sb.append(line).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Keeps only named keys from JSON-object-like outputs (deterministic subset). */
    @SuppressWarnings("unchecked")
    static String filterKeys(String raw, Map<String, Object> opts) {
        List<String> keys = strList(opts.get("keys"));
        if (keys.isEmpty()) return raw;
        Object parsed;
        try {
            parsed = WorkflowJson.mapper().readValue(raw, Object.class);
        } catch (Exception e) {
            return raw; // not JSON: fall back to raw (deterministic)
        }
        if (parsed instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (String k : keys) if (map.containsKey(k)) out.put(k, map.get(k));
            try {
                return WorkflowJson.mapper().writeValueAsString(out);
            } catch (Exception e) {
                return raw;
            }
        }
        if (parsed instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<Object, Object> filtered = new LinkedHashMap<>();
                    for (String k : keys) if (map.containsKey(k)) filtered.put(k, map.get(k));
                    out.add(filtered);
                } else {
                    out.add(item);
                }
            }
            try {
                return WorkflowJson.mapper().writeValueAsString(out);
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    /** Renders the input as fixed-structure bullets, filtered by include/exclude. */
    static String bulletize(String raw, Map<String, Object> opts) {
        String filtered = filterLines(raw, opts);
        int maxChars = intOpt(opts.get("maxChars"), 4000);
        StringBuilder sb = new StringBuilder();
        int current = 0;
        outer:
        for (String line : filtered.split("\n", -1)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("### ")) continue;
            String bullet = "- " + l + "\n";
            if (current + bullet.length() > maxChars) {
                sb.append("[... truncated ...]\n");
                break outer;
            }
            sb.append(bullet);
            current += bullet.length();
        }
        return sb.toString().stripTrailing();
    }

    private static String str(Object o, String dflt) {
        return o instanceof String s && !s.isBlank() ? s : dflt;
    }

    private static int intOpt(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return dflt;
    }

    private static List<String> strList(Object o) {
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) if (item != null) out.add(item.toString());
            return out;
        }
        if (o instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
        }
        return List.of();
    }

    private static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static Object tryParseJson(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (!(t.startsWith("{") || t.startsWith("["))) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(t, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}