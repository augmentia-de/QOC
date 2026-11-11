package de.augmentia.qoc.workflowrunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * ReactFlow-based workflow definition.
 * Primary format: Nodes + Edges (no v2 dependsOn).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(
        String id,
        String name,
        String version,
        String status,
        List<Node> nodes,
        List<Edge> edges,
        List<Agent> agents
) {
    public WorkflowDefinition {
        if (id == null) id = "";
        if (name == null) name = "";
        if (version == null) version = "1.0.0";
        if (status == null) status = "draft";
        if (nodes == null) nodes = List.of();
        if (edges == null) edges = List.of();
        if (agents == null) agents = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(
            String id,
            String type,
            Point position,
            NodeData data
    ) {
        public Node {
            if (id == null) id = "";
            if (type == null) type = "agent";
            if (position == null) position = new Point(0, 0);
            if (data == null) data = new NodeData("", "", false, "human", 3, "", List.of(), "all", null, List.of(), "", "");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(double x, double y) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeData(
            String title,
            String agentRef,
            boolean requiresHumanApproval,
            String assignee,
            int maxIterations,
            String exitCondition,
            List<String> bodySteps,
            String joinType,
            Integer joinN,
            List<Branch> branches,
            String defaultTarget,
            String workflowRef
    ) {
        public NodeData {
            if (title == null) title = "";
            if (agentRef == null) agentRef = "";
            if (assignee == null) assignee = "human";
            if (maxIterations <= 0) maxIterations = 3;
            if (exitCondition == null) exitCondition = "";
            if (bodySteps == null) bodySteps = List.of();
            if (joinType == null) joinType = "all";
            if (branches == null) branches = List.of();
            if (defaultTarget == null) defaultTarget = "";
            if (workflowRef == null) workflowRef = "";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Branch(String branchId, String condition, String target) {
        public Branch {
            if (branchId == null) branchId = "";
            if (condition == null) condition = "";
            if (target == null) target = "";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Edge(
            String id,
            String source,
            String target,
            String type,
            String condition
    ) {
        public Edge {
            if (id == null) id = "";
            if (source == null) source = "";
            if (target == null) target = "";
            if (type == null) type = "default";
            if (condition == null) condition = "";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Agent(
            String id,
            String name,
            String version,
            String category,
            String model,
            double temperature,
            int maxTokens,
            double topP,
            List<String> tools,
            List<String> guardrailsInput,
            List<String> guardrailsOutput,
            List<String> hooks,
            boolean jsonOutput,
            boolean active,
            String systemPrompt
    ) {
        public Agent {
            if (id == null) id = "";
            if (name == null) name = "";
            if (version == null) version = "1.0.0";
            if (category == null) category = "";
            if (model == null) model = "";
            if (tools == null) tools = List.of();
            if (guardrailsInput == null) guardrailsInput = List.of();
            if (guardrailsOutput == null) guardrailsOutput = List.of();
            if (hooks == null) hooks = List.of();
            if (systemPrompt == null) systemPrompt = "";
        }
    }
}
