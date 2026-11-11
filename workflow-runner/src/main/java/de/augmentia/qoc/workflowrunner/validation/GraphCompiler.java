package de.augmentia.qoc.workflowrunner.validation;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.runner.TopologicalSort;

import java.util.*;

/**
 * Validates workflow definitions (node types, edges, agent refs, cycles).
 * Ported from agentflow's GraphCompiler (simplified).
 */
public class GraphCompiler {

    private static final Set<String> VALID_NODE_TYPES = Set.of(
            "agent", "start", "decision", "loop", "fork", "join", "hitl", "subworkflow", "end",
            "llm_eval", "llm_summarize");

    public record ValidationResult(boolean valid, List<String> errors, String summary) {}

    public static ValidationResult validate(WorkflowDefinition def) {
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
            if ("agent".equals(n.type())) {
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
        for (WorkflowDefinition.Node n : def.nodes()) {
            if ("agent".equals(n.type()) && n.data() != null && n.data().agentRef() != null
                    && !n.data().agentRef().isBlank()) {
                if (!agentIds.contains(n.data().agentRef())) {
                    errors.add("Agent ref '" + n.data().agentRef() + "' not found in agents[]");
                }
            }
        }

        // Check for cycles
        try {
            TopologicalSort.sort(def);
        } catch (IllegalStateException e) {
            errors.add("Cycle detected in workflow graph");
        }

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
}
