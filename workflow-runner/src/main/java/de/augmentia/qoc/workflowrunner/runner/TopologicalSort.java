package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.util.*;

/**
 * Topological sort of workflow nodes based on edges (Kahn's algorithm).
 */
public final class TopologicalSort {

    private TopologicalSort() {}

    /**
     * Returns node IDs in topological order (dependencies first).
     * Throws if a cycle is detected.
     */
    public static List<String> sort(WorkflowDefinition def) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();

        for (WorkflowDefinition.Node n : def.nodes()) {
            inDegree.put(n.id(), 0);
            adjacency.put(n.id(), new ArrayList<>());
        }

        for (WorkflowDefinition.Edge e : def.edges()) {
            String src = e.source();
            String tgt = e.target();
            if (inDegree.containsKey(tgt) && adjacency.containsKey(src)) {
                adjacency.get(src).add(tgt);
                inDegree.put(tgt, inDegree.get(tgt) + 1);
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            result.add(node);
            for (String neighbor : adjacency.getOrDefault(node, List.of())) {
                int deg = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, deg);
                if (deg == 0) queue.add(neighbor);
            }
        }

        if (result.size() != inDegree.size()) {
            throw new IllegalStateException("Cycle detected in workflow graph");
        }

        return result;
    }

    /**
     * Returns all incoming edge sources for a given node.
     */
    public static List<String> incomingSources(WorkflowDefinition def, String nodeId) {
        List<String> sources = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.target().equals(nodeId)) {
                sources.add(e.source());
            }
        }
        return sources;
    }

    /**
     * Returns all outgoing edge targets for a given node.
     */
    public static List<String> outgoingTargets(WorkflowDefinition def, String nodeId) {
        List<String> targets = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.source().equals(nodeId)) {
                targets.add(e.target());
            }
        }
        return targets;
    }
}
