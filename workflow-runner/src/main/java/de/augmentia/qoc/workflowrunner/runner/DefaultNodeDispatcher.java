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
import de.augmentia.qoc.workflowrunner.store.AgentStorePort;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowJson;

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
    private final AgentStorePort agentStore;
    private final de.augmentia.oc.engine.core.model.Principal principal;

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
        this.engine = engine;
        this.store = store;
        this.expander = store != null ? new SubWorkflowExpander(store) : null;
        this.mcpManager = mcpManager;
        this.agentStore = agentStore;
        this.principal = principal == null ? de.augmentia.oc.engine.core.model.Principal.anonymous() : principal;
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
                if (agentDef != null && !agentAllowed(agentDef, principal)) {
                    return new NodeResult("Agent access denied: " + agentRef, null);
                }
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

                return runAgent(node, prefixedName, nodeInput, config, ctx, principal);
            }
            case "context_agent_executor" -> {
                String agentRef = node.data().agentRef();
                WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);
                if (agentDef != null && !agentAllowed(agentDef, principal)) {
                    return new NodeResult("Agent access denied: " + agentRef, null);
                }

                // Eigener Registry-Name pro Knoten, damit der Systemprompt dieses
                // Knotens nicht durch eine bereits registrierte Agent-Config
                // (resolveAgentConfig: first registration wins) überschrieben wird.
                String prefixedName = "wf:ctx:" + node.id();

                String fullContext = buildFullContext(def, ctx);

                String systemPrompt = resolveSystemPrompt(def, agentDef);

                String task = node.data().title();
                if (task == null || task.isBlank()) task = node.data().defaultTarget();
                if (task == null || task.isBlank()) task = "Continue with the next step.";

                String userPrompt = "## Previous context\n" + fullContext
                        + "\n\n## Current task\n" + task;

                QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName, systemPrompt);

                // Kein Runtime-Compaction in diesem Lauf.
                QocEngine.ExecutionOverrides overrides = new QocEngine.ExecutionOverrides(
                        null, null, null, null, null, null, false);

                int estimated = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
                if (estimated > CONTEXT_BUDGET_TOKENS) {
                    LOG.warn("context_agent_executor {}: estimated {} tokens exceed budget {}. "
                            + "Insert a compact step before this node if the run fails.",
                            node.id(), estimated, CONTEXT_BUDGET_TOKENS);
                }

                return runAgent(node, prefixedName, userPrompt, config, ctx, principal, overrides);
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
            case "compact" -> {
                return dispatchCompact(node, def, ctx, principal);
            }
            case "llm_eval" -> {
                String prompt = node.data().title();
                if (prompt == null || prompt.isBlank()) {
                    prompt = "Evaluate: " + ctx.getState("lastResult");
                }
                prompt = TemplateResolver.resolve(prompt, ctx);
                String prefixedName = "wf:llm_eval:" + node.id();
                boolean structuredOutput = node.data().jsonOutput() == null || node.data().jsonOutput();
                de.augmentia.oc.engine.core.model.Option<de.augmentia.oc.engine.core.json.Schema> schemaOption =
                        de.augmentia.oc.engine.core.model.Option.none();
                if (node.data().responseSchema() != null) {
                    de.augmentia.oc.engine.core.json.Result<de.augmentia.oc.engine.core.json.Schema> parsed =
                            de.augmentia.oc.engine.core.json.JsonSchema.parse(
                                    de.augmentia.oc.engine.core.json.JsonValues.fromJava(node.data().responseSchema()));
                    de.augmentia.oc.engine.core.json.Schema parsedSchema = parsed.valueOrNull();
                    if (parsedSchema == null) {
                        throw new IllegalArgumentException("Invalid response schema for node " + node.id());
                    }
                    schemaOption = de.augmentia.oc.engine.core.model.Option.some(parsedSchema);
                }
                QocEngine.AgentConfig config = new QocEngine.AgentConfig(
                        prefixedName, null, 0.3, 4096, 1.0,
                        "You are an evaluation assistant. Respond with valid JSON.",
                        List.of(), List.of(), List.of(), List.of(), structuredOutput,
                        schemaOption);
                return runAgent(node, prefixedName, prompt, config, ctx, principal);
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
                return runAgent(node, prefixedName, prompt, config, ctx, principal);
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
                                QocEngine.AgentConfig config, WorkflowContext ctx,
                                de.augmentia.oc.engine.core.model.Principal principal) {
        return runAgent(node, prefixedName, prompt, config, ctx, principal, null);
    }

    private NodeResult runAgent(WorkflowDefinition.Node node, String prefixedName, String prompt,
                                QocEngine.AgentConfig config, WorkflowContext ctx,
                                de.augmentia.oc.engine.core.model.Principal principal,
                                QocEngine.ExecutionOverrides overrides) {
        if (principal == null) principal = de.augmentia.oc.engine.core.model.Principal.anonymous();
        // Keep the child session so the parent run journal can link to it.
        SessionId child = engine.createSession(prefixedName, principal.id(), "workspace");
        EngineResult result = engine.runPrompt(child, prefixedName, prompt, config, true,
                null, null, principal, overrides);
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

    /** Overload mit Systemprompt-Override (für context_agent_executor). */
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

    /** RBAC: principal may use the agent? '*' in rolesAllowed means everyone. */
    private static boolean agentAllowed(WorkflowDefinition.Agent a, Principal p) {
        if (a == null) return true;
        List<String> allowed = a.rolesAllowed();
        if (allowed == null || allowed.isEmpty() || allowed.contains("*")) return true;
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
     * Liefert den Systemprompt für einen context_agent_executor-Knoten:
     * der gemeinsame Workflow-Prompt (für Prompt-Caching), falls gesetzt,
     * sonst der Agent-Systemprompt. Der Kontext liegt immer in der
     * User-Nachricht, nie im Systemprompt.
     */
    static String resolveSystemPrompt(WorkflowDefinition def, WorkflowDefinition.Agent agentDef) {
        String shared = def.systemPrompt();
        if (shared != null && !shared.isBlank()) return shared;
        return agentDef == null || agentDef.systemPrompt() == null ? "" : agentDef.systemPrompt();
    }

    /**
     * Baut den vollständigen Workflow-Kontext als Prompt-Text: Initial-Prompt,
     * Node-Outputs in Definition-Reihenfolge und sortierter Workflow-State.
     * Deterministisch, damit der Prompt über Läufe hinweg stabil (cachefähig) ist.
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
                .filter(e -> !"lastResult".equals(e.getKey())) // steht bereits in den Node-Outputs
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

    /** Grobe Token-Schätzung; nur für die Warnung vor dem Lauf (kein Compaction). */
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
}