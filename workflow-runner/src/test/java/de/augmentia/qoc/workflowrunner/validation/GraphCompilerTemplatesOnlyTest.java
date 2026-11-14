package de.augmentia.qoc.workflowrunner.validation;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GraphCompiler#validateTemplatesOnly} backs the save endpoint.
 *
 * <p>It must reject broken templates but keep half-finished drafts saveable:
 * a graph without an {@code end} node, dangling agent refs or missing edges is
 * normal while editing, whereas an unresolvable placeholder is a defect.</p>
 */
class GraphCompilerTemplatesOnlyTest {

    private static WorkflowDefinition.Node node(String id, String type, String title,
                                                Map<String, Object> mcpArgs) {
        return new WorkflowDefinition.Node(id, type, new WorkflowDefinition.Point(0, 0),
                new WorkflowDefinition.NodeData(title, "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null,
                        "", "", mcpArgs, null));
    }

    private static WorkflowDefinition def(WorkflowDefinition.Node... nodes) {
        return new WorkflowDefinition("wf", "wf", "1.0", "draft", List.of(nodes), List.of(),
                List.of(), "owner", "");
    }

    @Test
    void unfinishedDraftIsSaveable() {
        // No end node, no edges, no agents — but templates are fine.
        WorkflowDefinition def = def(node("llm-eval-1", "llm_eval", "Evaluate: ${lastResult}", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validateTemplatesOnly(def);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));

        // The full validation still rejects the same graph.
        assertFalse(GraphCompiler.validate(def).valid());
    }

    @Test
    void mustacheTemplateIsRejectedOnSave() {
        WorkflowDefinition def = def(node("llm-eval-1", "llm_eval", "Evaluate: {{lastResult}}", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validateTemplatesOnly(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("{{lastResult}}")),
                result.errors().toString());
    }

    @Test
    void unknownNodeReferenceIsRejectedOnSave() {
        WorkflowDefinition def = def(node("n1", "llm_eval", "${ghost.output.x}", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validateTemplatesOnly(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("ghost")), result.errors().toString());
    }

    @Test
    void knownNodeReferenceIsAccepted() {
        WorkflowDefinition def = def(
                node("node_1", "llm_eval", "${node_1.output.x}", Map.of()),
                node("node_2", "llm_eval", "use ${node_1.output}", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validateTemplatesOnly(def);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }

    @Test
    void badToolArgumentIsRejectedOnSave() {
        WorkflowDefinition def = def(node("mcp-1", "mcp_call", "call",
                Map.of("url", "https://x/${ghost.output.id}")));
        GraphCompiler.ValidationResult result = GraphCompiler.validateTemplatesOnly(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("mcpArgs.url")),
                result.errors().toString());
    }

    @Test
    void nullDefinitionIsRejected() {
        assertFalse(GraphCompiler.validateTemplatesOnly(null).valid());
    }
}
