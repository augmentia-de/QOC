package de.augmentia.qoc.workflowrunner.generate;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link WorkflowDefinition} from a parsed {@link WorkflowPlanParser.WorkbookResult}.
 * Maps GeneratedSteps to Nodes/Edges/Agents. No LLM, no validation beyond structural mapping.
 */
public final class WorkflowBuilder {

    private WorkflowBuilder() {}

    public static WorkflowDefinition build(WorkflowPlanParser.WorkbookResult result) {
        if (result == null || result.steps() == null || result.steps().isEmpty()) {
            throw new IllegalArgumentException("WorkbookResult must contain at least one step");
        }

        // Agents: one per unique agentRole.
        Map<String, WorkflowDefinition.Agent> agentsByRole = new LinkedHashMap<>();
        for (WorkflowPlanParser.GeneratedStep step : result.steps()) {
            String role = step.agentRole();
            if (!agentsByRole.containsKey(role)) {
                agentsByRole.put(role, new WorkflowDefinition.Agent(
                        role, role, "1.0.0", "", "", 0.7, 4096, 1.0,
                        step.tools() == null ? List.of() : List.copyOf(step.tools()),
                        List.of(), List.of(), List.of(), false, true,
                        step.systemPrompt() != null ? step.systemPrompt() : step.instruction(),
                        null, "", List.of("*"), "all", List.of()));
            }
        }

        // Nodes: one per step, positioned top-to-bottom, plus start/end nodes.
        List<WorkflowDefinition.Node> nodes = new ArrayList<>();
        int y = 0;
        // start node
        nodes.add(new WorkflowDefinition.Node("start", "start",
                new WorkflowDefinition.Point(100, y),
                new WorkflowDefinition.NodeData("Start", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null, null, List.of())));
        y += 120;
        for (WorkflowPlanParser.GeneratedStep step : result.steps()) {
            Map<String, Object> mcpArgs = Map.of();
            WorkflowDefinition.NodeData data = new WorkflowDefinition.NodeData(
                    step.agentRole(), step.agentRole(), false, "human", 3, "",
                    List.of(), "all", null, List.of(), "", "", null, "", "", mcpArgs, null,
                    null, List.of(), false, List.of());
            nodes.add(new WorkflowDefinition.Node(
                    step.agentRole(), "agent", new WorkflowDefinition.Point(100, y),
                    data));
            y += 120;
        }
        // end node
        nodes.add(new WorkflowDefinition.Node("end", "end",
                new WorkflowDefinition.Point(100, y),
                new WorkflowDefinition.NodeData("Done", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null, null, List.of())));

        // Edges: start -> roots, dep -> dependent, leaves -> end.
        List<WorkflowDefinition.Edge> edges = new ArrayList<>();
        int edgeIdx = 0;
        Set<String> edgeKeys = new LinkedHashSet<>();
        Set<String> depended = new LinkedHashSet<>();
        for (WorkflowPlanParser.GeneratedStep step : result.steps()) {
            for (String dep : step.dependsOn()) depended.add(dep);
        }
        // start -> roots (agents with no dependsOn)
        for (WorkflowPlanParser.GeneratedStep step : result.steps()) {
            if (step.dependsOn() == null || step.dependsOn().isEmpty()) {
                String key = "start->" + step.agentRole();
                if (edgeKeys.add(key)) {
                    edges.add(new WorkflowDefinition.Edge("e" + (edgeIdx++), "start", step.agentRole(), "default", ""));
                }
            }
        }
        // dep edges
        for (WorkflowPlanParser.GeneratedStep step : result.steps()) {
            for (String dep : step.dependsOn()) {
                String key = dep + "->" + step.agentRole();
                if (edgeKeys.add(key)) {
                    edges.add(new WorkflowDefinition.Edge("e" + (edgeIdx++), dep, step.agentRole(), "default", ""));
                }
            }
        }
        // leaves -> end
        for (WorkflowPlanParser.GeneratedStep step : result.steps()) {
            if (!depended.contains(step.agentRole())) {
                String key = step.agentRole() + "->end";
                if (edgeKeys.add(key)) {
                    edges.add(new WorkflowDefinition.Edge("e" + (edgeIdx++), step.agentRole(), "end", "default", ""));
                }
            }
        }
        // Fallback: if no roots (all have deps, e.g. cycle), connect start to first step
        if (edges.stream().noneMatch(e -> "start".equals(e.source()))) {
            String first = result.steps().get(0).agentRole();
            edges.add(0, new WorkflowDefinition.Edge("e" + (edgeIdx++), "start", first, "default", ""));
        }
        // Fallback: if no leaves, connect last step to end
        if (edges.stream().noneMatch(e -> "end".equals(e.target()))) {
            String last = result.steps().get(result.steps().size() - 1).agentRole();
            edges.add(new WorkflowDefinition.Edge("e" + (edgeIdx++), last, "end", "default", ""));
        }

        String name = result.workflowName() != null && !result.workflowName().isBlank()
                ? result.workflowName() : "generated-workflow";
        String id = "wf-" + System.currentTimeMillis();

        return new WorkflowDefinition(id, name, "1.0.0", "draft",
                nodes, edges, new ArrayList<>(agentsByRole.values()), "", "");
    }
}
