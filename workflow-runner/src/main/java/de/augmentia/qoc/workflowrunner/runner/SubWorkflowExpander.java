package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.WorkflowStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens sub-workflow nodes into the parent workflow.
 * Sub-workflow nodes (type="subworkflow") are replaced by their inlined
 * child nodes, with IDs prefixed by the sub-workflow node id.
 * Edges are remapped to use prefixed IDs.
 * Max nesting depth: 5.
 */
public class SubWorkflowExpander {

    private static final int MAX_DEPTH = 5;

    private final WorkflowStore store;

    public SubWorkflowExpander(WorkflowStore store) {
        this.store = store;
    }

    public WorkflowDefinition expand(WorkflowDefinition wf) {
        List<WorkflowDefinition.Node> flatNodes = new ArrayList<>();
        List<WorkflowDefinition.Edge> flatEdges = new ArrayList<>();
        Map<String, String> alias = new HashMap<>();
        List<WorkflowDefinition.Agent> collectedAgents = new ArrayList<>(wf.agents());

        expandWorkflow(wf, "", 0, flatNodes, flatEdges, alias, collectedAgents);

        // Remap edges: resolve aliases
        List<WorkflowDefinition.Edge> rewrittenEdges = new ArrayList<>();
        for (WorkflowDefinition.Edge e : flatEdges) {
            String src = resolve(e.source(), alias);
            String tgt = resolve(e.target(), alias);
            rewrittenEdges.add(new WorkflowDefinition.Edge(e.id(), src, tgt, e.type(), e.condition()));
        }

        // Deduplicate agents by id
        List<WorkflowDefinition.Agent> agents = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (WorkflowDefinition.Agent a : collectedAgents) {
            if (seen.add(a.id())) agents.add(a);
        }

        return new WorkflowDefinition(
                wf.id(), wf.name(), wf.version(), wf.status(),
                flatNodes, rewrittenEdges, agents
        );
    }

    private void expandWorkflow(WorkflowDefinition wf, String prefix, int depth,
                               List<WorkflowDefinition.Node> outNodes,
                               List<WorkflowDefinition.Edge> outEdges,
                               Map<String, String> alias,
                               List<WorkflowDefinition.Agent> collectedAgents) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("subworkflow nesting exceeds " + MAX_DEPTH);
        }

        // Expand nodes
        for (WorkflowDefinition.Node node : wf.nodes()) {
            String id = prefix.isEmpty() ? node.id() : prefix + "/" + node.id();

            if ("subworkflow".equals(node.type())) {
                String ref = (node.data() != null) ? node.data().workflowRef() : "";
                WorkflowDefinition subWf = store.get(ref);
                if (subWf == null) {
                    throw new IllegalArgumentException("Sub-workflow not found: " + ref);
                }
                // Merge agents from sub-workflow
                if (subWf.agents() != null) collectedAgents.addAll(subWf.agents());
                expandWorkflow(subWf, id, depth + 1, outNodes, outEdges, alias, collectedAgents);
                alias.put(id, id + "/end");
                // Add sub-workflow edges (prefixed)
                for (WorkflowDefinition.Edge e : subWf.edges()) {
                    outEdges.add(new WorkflowDefinition.Edge(
                            id + "/" + e.id(),
                            id + "/" + e.source(),
                            id + "/" + e.target(),
                            e.type(),
                            e.condition()));
                }
                continue;
            }

            outNodes.add(new WorkflowDefinition.Node(
                    id, node.type(), node.position(), node.data()));
        }

        // Add edges (prefixed)
        for (WorkflowDefinition.Edge e : wf.edges()) {
            String src = prefix.isEmpty() ? e.source() : prefix + "/" + e.source();
            String tgt = prefix.isEmpty() ? e.target() : prefix + "/" + e.target();
            outEdges.add(new WorkflowDefinition.Edge(e.id(), src, tgt, e.type(), e.condition()));
        }
    }

    private String resolve(String id, Map<String, String> alias) {
        String current = id;
        for (int i = 0; i <= MAX_DEPTH + 2; i++) {
            String next = alias.get(current);
            if (next == null || next.equals(current)) {
                return current;
            }
            current = next;
        }
        return current;
    }
}
