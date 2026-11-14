package de.augmentia.qoc.workflowrunner.validation;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.runner.TemplateResolver;
import de.augmentia.qoc.workflowrunner.runner.TopologicalSort;

import java.util.*;

/**
 * Validates workflow definitions (node types, edges, agent refs, cycles).
 * Ported from agentflow's GraphCompiler (simplified).
 */
public class GraphCompiler {

    private static final Set<String> VALID_NODE_TYPES = Set.of(
            "agent", "start", "decision", "loop", "fork", "join", "hitl", "subworkflow", "end",
            "llm_eval", "llm_summarize", "mcp_call", "rest_call", "context_agent_executor", "compact",
            "guard", "inject");

    /** Knotentypen, die einen Agenten (agentRef) benötigen. */
    private static final Set<String> AGENT_NODE_TYPES =
            Set.of("agent", "context_agent_executor");

    public record ValidationResult(boolean valid, List<String> errors, String summary) {}

    public static ValidationResult validate(WorkflowDefinition def) {
        return validate(def, Set.of());
    }

    /**
     * Validates a definition and additionally accepts agents that live outside
     * the definition (global agent store, Option B). {@code externalAgentIds}
     * may be empty for self-contained definitions.
     */
    public static ValidationResult validate(WorkflowDefinition def, Set<String> externalAgentIds) {
        List<String> errors = new ArrayList<>();
        if (def == null) {
            return new ValidationResult(false, List.of("Definition is null"), null);
        }
        if (def.id() == null || def.id().isBlank()) {
            errors.add("Workflow ID is required");
        }
        if (def.nodes() == null || def.nodes().isEmpty()) {
            errors.add("Workflow must have at least one node");
        }

        // Check node types
        Set<String> nodeIds = new HashSet<>();
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id() == null || n.id().isBlank()) {
                errors.add("Node ID is required");
            }
            if (nodeIds.contains(n.id())) {
                errors.add("Duplicate node ID: " + n.id());
            }
            nodeIds.add(n.id());
            if (!VALID_NODE_TYPES.contains(n.type())) {
                errors.add("Invalid node type '" + n.type() + "' for node " + n.id());
            }
            // Agent nodes must have agentRef
            if (AGENT_NODE_TYPES.contains(n.type())) {
                if (n.data() == null || n.data().agentRef() == null || n.data().agentRef().isBlank()) {
                    errors.add("Agent node " + n.id() + " requires agentRef");
                }
            }
        }

        // Check edges
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (!nodeIds.contains(e.source())) {
                errors.add("Edge source '" + e.source() + "' not found");
            }
            if (!nodeIds.contains(e.target())) {
                errors.add("Edge target '" + e.target() + "' not found");
            }
        }

        // Check agent refs
        Set<String> agentIds = new HashSet<>();
        for (WorkflowDefinition.Agent a : def.agents()) {
            agentIds.add(a.id());
        }
        if (externalAgentIds != null) agentIds.addAll(externalAgentIds);
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (AGENT_NODE_TYPES.contains(n.type()) && n.data() != null
                    && n.data().agentRef() != null
                    && !n.data().agentRef().isBlank()) {
                if (!agentIds.contains(n.data().agentRef())) {
                    errors.add("Agent ref '" + n.data().agentRef() + "' not found in agents[]");
                }
            }
        }

        // Zyklen sind fuer edge-basierte Loops erlaubt; die Ausfuehrung wird
        // durch das globale Step-Limit und den Circuit-Breaker begrenzt.

        // Template-Validierung: Platzhalter, die der Resolver nicht kennt,
        // landen sonst unaufgeloest im Prompt des LLM.
        errors.addAll(validateTemplates(def, nodeIds));

        // Check for exactly one end node
        long endCount = def.nodes().stream().filter(n -> "end".equals(n.type())).count();
        if (endCount == 0) {
            errors.add("Workflow must have at least one 'end' node");
        }

        String summary = errors.isEmpty()
                ? def.name() + " (" + def.nodes().size() + " nodes, " + def.agents().size() + " agents)"
                : null;

        return new ValidationResult(errors.isEmpty(), errors, summary);
    }

    /**
     * Template-only validation, usable while a definition is still a draft.
     *
     * <p>Deliberately weaker than {@link #validate}: it does not require an
     * {@code end} node, agent refs or a connected graph, so the editor can save
     * work in progress. Templates, however, are checked strictly — an
     * unresolvable placeholder is a defect at any stage and can never be fixed
     * by adding nodes later... except by creating the missing node, which is
     * exactly why unknown references are reported as errors only when node IDs
     * are compared.</p>
     */
    public static ValidationResult validateTemplatesOnly(WorkflowDefinition def) {
        if (def == null) {
            return new ValidationResult(false, List.of("Definition is null"), null);
        }
        Set<String> nodeIds = new HashSet<>();
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id() != null && !n.id().isBlank()) nodeIds.add(n.id());
        }
        List<String> errors = validateTemplates(def, nodeIds);
        String summary = errors.isEmpty()
                ? def.name() + " (" + def.nodes().size() + " nodes, templates ok)"
                : null;
        return new ValidationResult(errors.isEmpty(), errors, summary);
    }

    /**
     * Validates every placeholder-bearing string field of every node.
     *
     * <p>Checked fields: {@code title} (prompt templates), {@code defaultTarget}
     * (llm_summarize input), {@code exitCondition} and branch conditions
     * (condition paths), plus string values in {@code mcpArgs} (tool arguments).
     * Node IDs with hyphens are supported because that is what the editor
     * generates.</p>
     */
    private static List<String> validateTemplates(WorkflowDefinition def, Set<String> nodeIds) {
        List<String> errors = new ArrayList<>();
        for (WorkflowDefinition.Node n : def.nodes()) {
            WorkflowDefinition.NodeData d = n.data();
            if (d == null) continue;
            checkTemplate(n.id(), "title", d.title(), nodeIds, errors);
            checkTemplate(n.id(), "defaultTarget", d.defaultTarget(), nodeIds, errors);
            checkCondition(n.id(), "exitCondition", d.exitCondition(), nodeIds, errors);
            for (WorkflowDefinition.Branch b : d.branches()) {
                checkCondition(n.id(), "branch condition", b.condition(), nodeIds, errors);
            }
            for (Map.Entry<String, Object> arg : d.mcpArgs().entrySet()) {
                if (arg.getValue() instanceof String s) {
                    checkTemplate(n.id(), "mcpArgs." + arg.getKey(), s, nodeIds, errors);
                }
            }
        }
        return errors;
    }

    private static void checkTemplate(String nodeId, String field, String template,
                                      Set<String> nodeIds, List<String> errors) {
        for (String problem : TemplateResolver.findProblems(template, nodeIds, true)) {
            errors.add("Node " + nodeId + " (" + field + "): " + problem);
        }
    }

    private static void checkCondition(String nodeId, String field, String condition,
                                       Set<String> nodeIds, List<String> errors) {
        for (String problem : TemplateResolver.findConditionProblems(condition, nodeIds)) {
            errors.add("Node " + nodeId + " (" + field + "): " + problem);
        }
    }
}
