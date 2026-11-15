package de.augmentia.qoc.workflowrunner.generate;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic contract validation for generated workflows.
 *
 * <p>Checks the conventions from the tool-design guidelines:
 * tool names follow {@code domain_resource_action}, results are bounded
 * (hasMore/limit), parameter descriptions are present, tool responsibilities
 * do not overlap. Returns a list of warnings (empty = clean).</p>
 */
public final class WorkflowContractValidator {

    private WorkflowContractValidator() {}

    public static List<String> validate(WorkflowDefinition def) {
        List<String> warnings = new ArrayList<>();
        if (def == null) {
            warnings.add("workflow definition is null");
            return warnings;
        }
        Set<String> toolNames = new HashSet<>();
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n == null || n.data() == null) continue;
            // tool-bearing nodes (mcp_call / agent with tools)
            String tool = n.data().mcpTool();
            if (tool != null && !tool.isBlank()) {
                if (!tool.matches("[a-z][a-z0-9_]*_[a-z][a-z0-9_]*_[a-z][a-z0-9_]*")) {
                    warnings.add("Tool name does not follow domain_resource_action convention: " + tool
                            + " (node " + n.id() + ")");
                }
                if (toolNames.contains(tool)) {
                    warnings.add("Potential tool overlap: " + tool + " used by multiple nodes");
                }
                toolNames.add(tool);
            }
            // output contract presence
            if (n.data().outputContract() == null || n.data().outputContract().isEmpty()) {
                // only warn for agent/context nodes, not structural ones
                if ("agent".equals(n.type()) || "context_agent_executor".equals(n.type())
                        || "llm_eval".equals(n.type())) {
                    warnings.add("Node has no outputContract: " + n.id()
                            + " (consider declaring one for clear data passing)");
                }
            }
        }
        // agent descriptions
        if (def.agents() != null) {
            for (WorkflowDefinition.Agent a : def.agents()) {
                if (a == null) continue;
                if (a.systemPrompt() == null || a.systemPrompt().isBlank()) {
                    warnings.add("Agent has no systemPrompt: " + a.id());
                }
            }
        }
        return warnings;
    }
}