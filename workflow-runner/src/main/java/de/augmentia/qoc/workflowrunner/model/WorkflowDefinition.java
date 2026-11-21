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
        List<Agent> agents,
        String ownerId,
        String systemPrompt,
        Map<String, Object> inputSchema
) {
    public WorkflowDefinition {
        if (id == null) id = "";
        if (name == null) name = "";
        if (version == null) version = "1.0.0";
        if (status == null) status = "draft";
        if (nodes == null) nodes = List.of();
        if (edges == null) edges = List.of();
        if (agents == null) agents = List.of();
        if (ownerId == null) ownerId = "";
        if (systemPrompt == null) systemPrompt = "";
        // inputSchema may be null
    }

    /** Backwards-compatible constructor for callers without a shared system prompt. */
    public WorkflowDefinition(String id, String name, String version, String status,
                              List<Node> nodes, List<Edge> edges, List<Agent> agents,
                              String ownerId) {
        this(id, name, version, status, nodes, edges, agents, ownerId, "", null);
    }

    /** Backwards-compatible constructor for callers without an input schema. */
    public WorkflowDefinition(String id, String name, String version, String status,
                              List<Node> nodes, List<Edge> edges, List<Agent> agents,
                              String ownerId, String systemPrompt) {
        this(id, name, version, status, nodes, edges, agents, ownerId, systemPrompt, null);
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
            if (data == null) data = new NodeData("", "", false, "human", 3, "", List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null, null, List.of(), false);
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
            String workflowRef,
            Map<String, Object> responseSchema,
            String mcpServer,
            String mcpTool,
            Map<String, Object> mcpArgs,
            Boolean jsonOutput,
            Map<String, Object> outputContract,
            List<String> contextRefs,
            Boolean strict,
            List<String> verifyOnStop
    ) {
        /** Backwards-compatible constructor without outputContract/contextRefs (legacy callers). */
    public NodeData(String title, String agentRef, boolean requiresHumanApproval, String assignee,
                    int maxIterations, String exitCondition, List<String> bodySteps, String joinType,
                    Integer joinN, List<Branch> branches, String defaultTarget, String workflowRef,
                    Map<String, Object> responseSchema, String mcpServer, String mcpTool,
                    Map<String, Object> mcpArgs, Boolean jsonOutput) {
        this(title, agentRef, requiresHumanApproval, assignee, maxIterations, exitCondition, bodySteps,
                joinType, joinN, branches, defaultTarget, workflowRef, responseSchema, mcpServer, mcpTool,
                mcpArgs, jsonOutput, null, List.of(), false, List.of());
    }

    /** Backwards-compatible constructor without strict flag. */
    public NodeData(String title, String agentRef, boolean requiresHumanApproval, String assignee,
                    int maxIterations, String exitCondition, List<String> bodySteps, String joinType,
                    Integer joinN, List<Branch> branches, String defaultTarget, String workflowRef,
                    Map<String, Object> responseSchema, String mcpServer, String mcpTool,
                    Map<String, Object> mcpArgs, Boolean jsonOutput,
                    Map<String, Object> outputContract, List<String> contextRefs) {
        this(title, agentRef, requiresHumanApproval, assignee, maxIterations, exitCondition, bodySteps,
                joinType, joinN, branches, defaultTarget, workflowRef, responseSchema, mcpServer, mcpTool,
                mcpArgs, jsonOutput, outputContract, contextRefs, false);
    }

    public NodeData(String title, String agentRef, boolean requiresHumanApproval, String assignee,
                    int maxIterations, String exitCondition, List<String> bodySteps, String joinType,
                    Integer joinN, List<Branch> branches, String defaultTarget, String workflowRef,
                    Map<String, Object> responseSchema, String mcpServer, String mcpTool,
                    Map<String, Object> mcpArgs, Boolean jsonOutput,
                    Map<String, Object> outputContract, List<String> contextRefs, Boolean strict) {
        this(title, agentRef, requiresHumanApproval, assignee, maxIterations, exitCondition, bodySteps,
                joinType, joinN, branches, defaultTarget, workflowRef, responseSchema, mcpServer, mcpTool,
                mcpArgs, jsonOutput, outputContract, contextRefs, strict, List.of());
    }

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
            // responseSchema may be null (no structured output)
            if (mcpServer == null) mcpServer = "";
            if (mcpTool == null) mcpTool = "";
            if (mcpArgs == null) mcpArgs = Map.of();
            // outputContract may be null (no contract declared -> legacy passthrough)
            if (contextRefs == null) contextRefs = List.of();
            else contextRefs = List.copyOf(contextRefs);
            // strict may be null (default = false = warn + continue)
            if (strict == null) strict = false;
            if (verifyOnStop == null) verifyOnStop = List.of();
            else verifyOnStop = List.copyOf(verifyOnStop);
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
            String condition,
            String sourceHandle,
            String targetHandle
    ) {
        /** Backwards-compatible constructor for callers without handle metadata. */
        public Edge(String id, String source, String target, String type, String condition) {
            this(id, source, target, type, condition, "", "");
        }

        public Edge {
            if (id == null) id = "";
            if (source == null) source = "";
            if (target == null) target = "";
            if (type == null) type = "default";
            if (condition == null) condition = "";
            if (sourceHandle == null) sourceHandle = "";
            if (targetHandle == null) targetHandle = "";
        }
    }

    /**
     * An agent definition shared by the workflow runner and the QOC engine.
     *
     * <p>Two independent authorization concepts:</p>
     * <ul>
     *   <li>{@code rolesAllowed} — WHO may invoke/use this agent (agent-level
     *       access control). {@code *} = everyone. Empty = nobody.</li>
     *   <li>{@code permissions} — WHAT the agent may do (tool-level rules:
     *       {@code Rule(action, resource, effect)}). Empty = fall back to the
     *       global default mode (least-privilege).</li>
     * </ul>
     * <p>Presets (readonly/retrieval/editor/full) materialize into a concrete
     * {@code permissions} list together with a matching {@code tools} list.</p>
     */
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
            String systemPrompt,
            Map<String, Object> responseSchema,
            String ownerId,
            List<String> rolesAllowed,
            String mode,
            List<String> skills,
            int maxTurns,
            double maxCost,
            boolean requiresHITL,
            List<de.augmentia.oc.engine.core.model.Rule> permissions
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
            // responseSchema may be null (no structured output)
            if (ownerId == null) ownerId = "";
            if (rolesAllowed == null) rolesAllowed = List.of();
            else rolesAllowed = List.copyOf(rolesAllowed);
            // rolesAllowed: null/empty => nobody (not all). "*" => all.
            if (mode == null || mode.isBlank()) mode = "all";
            if (skills == null) skills = List.of();
            else skills = List.copyOf(skills);
            if (maxTurns < 0) maxTurns = 10;
            if (maxCost <= 0) maxCost = 1.0;
            if (permissions == null) permissions = List.of();
            else permissions = List.copyOf(permissions);
        }
    }
}
