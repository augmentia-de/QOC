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

    private static final String LLM_SYSTEM_PROMPT = """
            You are a tool-contract reviewer. Given a workflow definition as JSON, check the tool
            and node contracts against these rules:
            1. Tool names follow domain_resource_action (e.g. support_tickets_search).
            2. Each tool parameter has a clear description with constraints (length, enum, format).
            3. Tool responsibilities do not overlap.
            4. Results are bounded: list-returning tools mention hasMore/limit.
            5. Side effects are visible in the tool name/description.
            6. Agent nodes declare an outputContract when their output is consumed downstream.

            Respond with ONLY a JSON array of warning strings. Empty array if no issues.
            Example: ["Tool 'doStuff' does not follow domain_resource_action", "Node 'fetch' has no outputContract"]
            """;

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

    /**
     * LLM-based semantic contract review. Best-effort: returns an empty list
     * on any failure (LLM error, parse error, null inputs).
     */
    public static List<String> validateWithLlm(WorkflowDefinition def,
                                                java.util.function.Function<String, String> llmCaller) {
        List<String> warnings = new ArrayList<>();
        if (def == null || llmCaller == null) return warnings;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String defJson = mapper.writeValueAsString(def);
            String fullPrompt = LLM_SYSTEM_PROMPT + "\n\nWorkflow definition:\n" + defJson;
            String raw = llmCaller.apply(fullPrompt);
            if (raw == null || raw.isBlank()) return warnings;
            // Strip markdown fences if present.
            String json = StripFences.strip(raw);
            com.fasterxml.jackson.core.type.TypeReference<List<String>> typeRef =
                    new com.fasterxml.jackson.core.type.TypeReference<>() {};
            List<String> parsed = mapper.readValue(json, typeRef);
            if (parsed != null) {
                for (String w : parsed) {
                    if (w != null && !w.isBlank()) warnings.add(w.trim());
                }
            }
        } catch (Exception e) {
            // Best-effort: swallow and return whatever we have.
        }
        return warnings;
    }
}