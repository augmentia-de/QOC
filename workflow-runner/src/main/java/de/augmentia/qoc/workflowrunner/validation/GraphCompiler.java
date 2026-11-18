package de.augmentia.qoc.workflowrunner.validation;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.runner.TemplateResolver;
import de.augmentia.qoc.workflowrunner.runner.TopologicalSort;

import java.util.*;

/**
 * Validates workflow definitions (node types, edges, agent refs, cycles).
 * Ported from agentflow's GraphCompiler (simplified).
 *
 * <p>Additional SDLC-workflow invariants (spec: docs/spec/agents/05-aisdlc-workflow.md):</p>
 * <ul>
 *   <li><b>Gate-bypass invariant (SDW-07):</b> Every path from start to end
 *       that skips a {@code hitl} gate node must traverse a decision branch
 *       whose condition references the conservative change classification
 *       (delta markers). Any other bypass is a validation error.</li>
 *   <li><b>Budget binding (SDW-03):</b> Every edge that lies on a cycle must
 *       be anchored at a counted node (a {@code loop} node or a {@code hitl}
 *       node with {@code maxIterations > 1}); unbounded revisit loops are a
 *       validation error.</li>
 * </ul>
 */
public class GraphCompiler {

    private static final Set<String> VALID_NODE_TYPES = Set.of(
            "agent", "start", "decision", "loop", "fork", "join", "hitl", "subworkflow", "end",
            "mcp_call", "rest_call", "context_agent_executor", "compact",
            "guard", "inject", "classify");

    /** Node types that require an agent (agentRef). */
    private static final Set<String> AGENT_NODE_TYPES =
            Set.of("agent", "context_agent_executor");

    /**
     * Condition markers that mark a decision branch as the legal delta
     * (gate-skipping) path. Conservative change classification only:
     * contract_impact, breaking, scope — see spec agents/05, SDW-07.
     */
    private static final Set<String> DELTA_CONDITION_MARKERS = Set.of(
            "state.contract_impact", "state.change.contract_impact",
            "state.breaking", "state.change.breaking",
            "state.scope", "state.change.scope");

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
        Map<String, WorkflowDefinition.Node> nodeById = new LinkedHashMap<>();
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id() == null || n.id().isBlank()) {
                errors.add("Node ID is required");
            }
            if (nodeById.containsKey(n.id())) {
                errors.add("Duplicate node ID: " + n.id());
            }
            nodeById.put(n.id(), n);
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

        Set<String> nodeIds = new HashSet<>();
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id() != null && !n.id().isBlank()) nodeIds.add(n.id());
        }

        // Check edges
        List<WorkflowDefinition.Edge> edges = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (!nodeById.containsKey(e.source())) {
                errors.add("Edge source '" + e.source() + "' not found");
            }
            if (!nodeById.containsKey(e.target())) {
                errors.add("Edge target '" + e.target() + "' not found");
            }
            edges.add(e);
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

        // Cycles: edge-based loops are allowed; execution is bounded by the
        // global step limit and the circuit breaker. Additionally, revisit loops
        // must be budget-anchored (SDW-03): unanchored cycles are an error.
        try {
            TopologicalSort.sort(def);
        } catch (IllegalStateException e) {
            List<String> unanchored = unanchoredCycles(nodeById, edges);
            if (!unanchored.isEmpty()) {
                errors.add("Cycle detected in workflow graph (unanchored: "
                        + String.join(", ", unanchored) + ")");
            }
            // else: legal revisit loop - no error
        }

        // Template validation: placeholders unknown to the resolver,
        // otherwise they end up unresolved in the LLM prompt.
        errors.addAll(validateTemplates(def, nodeIds));

        // Check for at least one end node
        long endCount = def.nodes().stream().filter(n -> "end".equals(n.type())).count();
        if (endCount == 0) {
            errors.add("Workflow must have at least one 'end' node");
        }

        // Check fork/join shape...
        // a join at least two incoming edges. Otherwise the node is decorative.
        for (WorkflowDefinition.Node n : def.nodes()) {
            if ("fork".equals(n.type())) {
                if (TopologicalSort.outgoingTargets(def, n.id()).size() < 2) {
                    errors.add("Fork node " + n.id() + " requires at least two outgoing edges");
                }
            } else if ("join".equals(n.type())) {
                if (TopologicalSort.incomingSources(def, n.id()).size() < 2) {
                    errors.add("Join node " + n.id() + " requires at least two incoming edges");
                }
            }
        }

        // SDW-07: gate-bypass invariant
        errors.addAll(validateGateBypass(nodeById, edges));
        // SDW-03: budget binding on cyclic (revisit) edges
        errors.addAll(validateBudgetBinding(nodeById, edges));

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
     * ({@code defaultTarget} input), {@code exitCondition} and branch conditions
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

    // ------------------------------------------------------------------
    // SDW-07: gate-bypass invariant
    // ------------------------------------------------------------------

    /**
     * Gate nodes are {@code hitl} nodes. For each gate: enumerate simple paths
     * start → end that avoid the gate. Such a bypass path is legal only if it
     * contains at least one delta-classification edge (edge condition or
     * decision branch referencing the change classification).
     */
    static List<String> validateGateBypass(Map<String, WorkflowDefinition.Node> nodeById,
                                           List<WorkflowDefinition.Edge> edges) {
        List<String> errors = new ArrayList<>();

        Set<String> gates = new HashSet<>();
        for (WorkflowDefinition.Node n : nodeById.values()) {
            if ("hitl".equals(n.type())) gates.add(n.id());
        }
        if (gates.isEmpty()) {
            return errors;
        }

        Set<String> deltaEdges = deltaEdgeKeys(nodeById, edges);

        String start = findSingleNode(nodeById, "start");
        String end = findSingleNode(nodeById, "end");
        if (start == null || end == null) {
            return errors; // structural errors already reported
        }

        for (String gate : gates) {
            List<List<WorkflowDefinition.Edge>> bypassPaths =
                    pathsAvoiding(edges, start, end, gate, 50);
            for (List<WorkflowDefinition.Edge> path : bypassPaths) {
                boolean hasDeltaEdge = path.stream()
                        .anyMatch(e -> deltaEdges.contains(e.source() + "->" + e.target()));
                if (!hasDeltaEdge) {
                    errors.add("Gate-bypass violation (SDW-07): path from '" + start
                            + "' to '" + end + "' skips gate '" + gate
                            + "' without a delta-classification decision branch (via: "
                            + pathNodes(path) + ")");
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

    /**
     * Delta edges keyed as {@code source->target}. Sources: (a) plain edges
     * whose condition references the change-classification state keys (the
     * runtime navigator routes on edge conditions) and (b) decision node
     * branches with such conditions (declarative/UI form).
     */
    static Set<String> deltaEdgeKeys(Map<String, WorkflowDefinition.Node> nodeById,
                                     List<WorkflowDefinition.Edge> edges) {
        Set<String> deltaEdges = new HashSet<>();
        for (WorkflowDefinition.Edge e : edges) {
            if (isDeltaCondition(e.condition())) {
                deltaEdges.add(e.source() + "->" + e.target());
            }
        }
        for (WorkflowDefinition.Node n : nodeById.values()) {
            if (!"decision".equals(n.type()) || n.data() == null) continue;
            for (WorkflowDefinition.Branch b : n.data().branches()) {
                if (isDeltaCondition(b.condition())) {
                    deltaEdges.add(n.id() + "->" + b.target());
                }
            }
        }
        return deltaEdges;
    }

    private static boolean isDeltaCondition(String condition) {
        if (condition == null || condition.isBlank()) return false;
        for (String marker : DELTA_CONDITION_MARKERS) {
            if (condition.contains(marker)) return true;
        }
        return false;
    }

    /**
     * Enumerates simple paths from {@code from} to {@code to} avoiding
     * {@code avoid}, bounded by {@code maxPaths}. Process graphs are small, so
     * exhaustive DFS is acceptable.
     */
    static List<List<WorkflowDefinition.Edge>> pathsAvoiding(
            List<WorkflowDefinition.Edge> edges, String from, String to,
            String avoid, int maxPaths) {
        Map<String, List<WorkflowDefinition.Edge>> out = new HashMap<>();
        for (WorkflowDefinition.Edge e : edges) {
            out.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
        }
        List<List<WorkflowDefinition.Edge>> result = new ArrayList<>();
        Deque<WorkflowDefinition.Edge> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        visited.add(from);
        dfs(out, from, to, avoid, visited, stack, result, maxPaths);
        return result;
    }

    private static void dfs(Map<String, List<WorkflowDefinition.Edge>> out,
                            String current, String to, String avoid,
                            Set<String> visited, Deque<WorkflowDefinition.Edge> stack,
                            List<List<WorkflowDefinition.Edge>> result, int maxPaths) {
        if (result.size() >= maxPaths) return;
        if (current.equals(to)) {
            result.add(new ArrayList<>(stack));
            return;
        }
        for (WorkflowDefinition.Edge e : out.getOrDefault(current, List.of())) {
            if (e.target().equals(avoid) || visited.contains(e.target())) continue;
            visited.add(e.target());
            stack.push(e);
            dfs(out, e.target(), to, avoid, visited, stack, result, maxPaths);
            stack.pop();
            visited.remove(e.target());
        }
    }

    private static String pathNodes(List<WorkflowDefinition.Edge> path) {
        if (path.isEmpty()) return "?";
        StringBuilder sb = new StringBuilder(path.get(0).source());
        for (WorkflowDefinition.Edge e : path) sb.append(",").append(e.target());
        return sb.toString();
    }

    private static String findSingleNode(Map<String, WorkflowDefinition.Node> nodeById, String type) {
        List<String> ids = nodeById.values().stream()
                .filter(n -> type.equals(n.type()))
                .map(WorkflowDefinition.Node::id)
                .toList();
        return ids.size() == 1 ? ids.get(0) : null;
    }

    // ------------------------------------------------------------------
    // SDW-03: budget binding
    // ------------------------------------------------------------------

    /**
     * Every edge on a cycle must be anchored at a counted node. Anchoring
     * rule: the edge re-enters a counted node ({@code loop}, or {@code hitl}
     * with {@code maxIterations > 1}) at its <em>target</em> — i.e. the cycle
     * passes through the counted node, whose budget bounds how often the
     * cycle can repeat. Edges leaving a counted node are part of that
     * counted iteration and are legal. Unanchored return edges are a
     * validation error.
     */
    static List<String> validateBudgetBinding(Map<String, WorkflowDefinition.Node> nodeById,
                                              List<WorkflowDefinition.Edge> edges) {
        List<String> errors = new ArrayList<>();

        Set<String> countedNodes = countedNodeIds(nodeById);
        Map<String, Set<String>> adj = adjacency(edges);

        // A cycle is legal (budget-anchored) when it passes through at least
        // one counted node. An edge between two uncounted nodes is a
        // violation only if it lies on a cycle that touches no counted node:
        // then that edge would repeat without any budget. If every cycle
        // containing the edge also passes a counted node, the edge is legal
        // (the counted node's budget bounds the repetition).
        for (WorkflowDefinition.Edge e : edges) {
            if (!nodeById.containsKey(e.source()) || !nodeById.containsKey(e.target())) continue;
            if (countedNodes.contains(e.source()) || countedNodes.contains(e.target())) {
                continue; // touches a counted node - fine
            }
            if (!reachable(adj, e.target(), e.source())) {
                continue; // not on any cycle - fine
            }
            if (cycleThroughEdgeHasCountedNode(adj, countedNodes, e)) {
                continue; // every cycle through this edge is anchored - fine
            }
            errors.add("Budget-binding violation (SDW-03): edge '" + e.source()
                    + "' -> '" + e.target() + "' lies on a cycle that touches no counted "
                    + "node (loop or hitl with maxIterations > 1); it can repeat without a budget");
        }
        return errors;
    }

    /**
     * True when every cycle that passes through edge {@code e} also passes a
     * counted node. Implemented as: for the path target → … → source (closing
     * the cycle through e), every simple path from target back to source must
     * contain an edge touching a counted node.
     */
    private static boolean cycleThroughEdgeHasCountedNode(Map<String, Set<String>> adj,
                                                          Set<String> countedNodes,
                                                          WorkflowDefinition.Edge e) {
        return allPathsTouchCounted(adj, countedNodes, e.target(), e.source(),
                new HashSet<>(Set.of(e.target())), false);
    }

    /**
     * Returns true when every simple path from {@code from} to {@code to}
     * touches a counted node (edge or node). Empty result (no path) also
     * returns true — then the edge is not on a cycle at all.
     */
    private static boolean allPathsTouchCounted(Map<String, Set<String>> adj,
                                                Set<String> countedNodes,
                                                String from, String to,
                                                Set<String> visited, boolean sawCounted) {
        boolean anyPath = false;
        for (String next : adj.getOrDefault(from, Set.of())) {
            boolean countedHere = sawCounted || countedNodes.contains(from)
                    || countedNodes.contains(next);
            if (next.equals(to)) {
                anyPath = true;
                if (!countedHere) return false; // found an uncounted cycle path
                continue;
            }
            if (visited.add(next)) {
                anyPath = true;
                if (!allPathsTouchCounted(adj, countedNodes, next, to, visited, countedHere)) {
                    return false;
                }
                visited.remove(next);
            }
        }
        return !anyPath || true; // all paths counted (or no path at all)
    }

    static Set<String> countedNodeIds(Map<String, WorkflowDefinition.Node> nodeById) {
        Set<String> counted = new HashSet<>();
        for (WorkflowDefinition.Node n : nodeById.values()) {
            if ("loop".equals(n.type())) {
                counted.add(n.id());
            } else if ("hitl".equals(n.type()) && n.data() != null
                    && n.data().maxIterations() > 1) {
                counted.add(n.id());
            }
        }
        return counted;
    }

    static Map<String, Set<String>> adjacency(List<WorkflowDefinition.Edge> edges) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (WorkflowDefinition.Edge e : edges) {
            adj.computeIfAbsent(e.source(), k -> new HashSet<>()).add(e.target());
        }
        return adj;
    }

    /**
     * Returns node IDs that lie on at least one cycle where NO cycle through
     * the node passes a counted node ({@code loop}, or {@code hitl} with
     * {@code maxIterations > 1}). Nodes without any cycle are not reported.
     */
    static List<String> unanchoredCycles(Map<String, WorkflowDefinition.Node> nodeById,
                                         List<WorkflowDefinition.Edge> edges) {
        Set<String> countedNodes = countedNodeIds(nodeById);
        Map<String, Set<String>> adj = adjacency(edges);

        List<String> unanchored = new ArrayList<>();
        for (String nodeId : nodeById.keySet()) {
            if (onUnanchoredCycle(adj, countedNodes, nodeId)) {
                unanchored.add(nodeId);
            }
        }
        return unanchored;
    }

    private static boolean onUnanchoredCycle(Map<String, Set<String>> adj,
                                             Set<String> countedNodes, String nodeId) {
        Result r = new Result();
        dfsCycles(adj, countedNodes, nodeId, nodeId,
                new HashSet<>(Set.of(nodeId)), false, r);
        // Report only if a cycle exists through the node and no such cycle is
        // budget-anchored.
        return r.foundCycle && !r.foundCountedCycle;
    }

    private static final class Result {
        boolean foundCycle;
        boolean foundCountedCycle;
    }

    private static void dfsCycles(Map<String, Set<String>> adj, Set<String> countedNodes,
                                  String start, String current, Set<String> visited,
                                  boolean sawCountedEdge, Result r) {
        for (String next : adj.getOrDefault(current, Set.of())) {
            boolean countedEdge = countedNodes.contains(current)
                    || countedNodes.contains(next);
            boolean anyCounted = sawCountedEdge || countedEdge;
            if (next.equals(start)) {
                r.foundCycle = true;
                if (anyCounted) r.foundCountedCycle = true;
                continue;
            }
            if (r.foundCountedCycle) {
                return; // already proven: at least one cycle through start is anchored
            }
            if (visited.add(next)) {
                dfsCycles(adj, countedNodes, start, next, visited, anyCounted, r);
                visited.remove(next);
            }
        }
    }

    private static boolean reachable(Map<String, Set<String>> adj, String from, String to) {
        if (from.equals(to)) return true;
        Deque<String> stack = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        stack.push(from);
        seen.add(from);
        while (!stack.isEmpty()) {
            String cur = stack.pop();
            for (String next : adj.getOrDefault(cur, Set.of())) {
                if (next.equals(to)) return true;
                if (seen.add(next)) stack.push(next);
            }
        }
        return false;
    }

}
