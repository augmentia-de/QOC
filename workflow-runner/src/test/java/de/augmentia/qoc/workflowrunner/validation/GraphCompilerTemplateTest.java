package de.augmentia.qoc.workflowrunner.validation;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link GraphCompiler} rejects templates the runtime cannot
 * resolve.
 *
 * <p>Motivation: a definition carrying {@code {{lastResult}}} validated
 * successfully and then passed the placeholder to an LLM as if it were data.</p>
 */
class GraphCompilerTemplateTest {

    private static WorkflowDefinition.Node node(String id, String type, String title,
                                                String defaultTarget, String exitCondition,
                                                Map<String, Object> mcpArgs) {
        return new WorkflowDefinition.Node(id, type, new WorkflowDefinition.Point(0, 0),
                new WorkflowDefinition.NodeData(title, "", false, "human", 3, exitCondition,
                        List.of(), "all", null, List.of(), defaultTarget, "", null,
                        "", "", mcpArgs, null));
    }

    private static WorkflowDefinition def(WorkflowDefinition.Node... nodes) {
        return new WorkflowDefinition("wf", "wf", "1.0", "draft", List.of(nodes), List.of(),
                List.of(), "owner", "");
    }

    @Test
    void acceptsDefinitionWithoutPlaceholders() {
        WorkflowDefinition def = def(
                node("start", "start", "Start", "", "", Map.of()),
                node("end", "end", "Done", "", "", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }

    @Test
    void acceptsSupportedPlaceholderSyntax() {
        WorkflowDefinition def = def(
                node("start", "start", "Start", "", "", Map.of()),
                node("node_1", "llm_eval", "Evaluate: ${lastResult}", "", "", Map.of()),
                node("end", "end", "Done", "", "", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }

    @Test
    void rejectsMustachePlaceholderInTitle() {
        // Regression: this definition validated and then leaked {{lastResult}}.
        WorkflowDefinition def = def(
                node("start", "start", "Start", "", "", Map.of()),
                node("llm-eval-1", "llm_eval", "Evaluate: {{lastResult}}", "", "", Map.of()),
                node("end", "end", "Done", "", "", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("llm-eval-1")), result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("{{lastResult}}")), result.errors().toString());
    }

    @Test
    void rejectsUnknownNodeReference() {
        WorkflowDefinition def = def(
                node("start", "start", "Start", "", "", Map.of()),
                node("node_1", "llm_eval", "${node_99.output.name}", "", "", Map.of()),
                node("end", "end", "Done", "", "", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("node_99")), result.errors().toString());
    }

    @Test
    void rejectsHyphenatedNodeIdRegression() {
        // Editor-generated ids contain hyphens; they must be addressable.
        WorkflowDefinition def = def(
                node("start", "start", "Start", "", "", Map.of()),
                node("erp-prices", "llm_eval", "${erp-prices.output.total}", "", "", Map.of()),
                node("end", "end", "Done", "", "", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }

    @Test
    void rejectsUnknownNodeInMcpArgs() {
        WorkflowDefinition def = def(
                node("start", "start", "Start", "", "", Map.of()),
                node("mcp-1", "mcp_call", "call", "", "",
                        Map.of("url", "https://x/${node_99.output.id}")),
                node("end", "end", "Done", "", "", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("mcpArgs.url")), result.errors().toString());
    }

    @Test
    void rejectsUnknownNodeInCondition() {
        WorkflowDefinition def = def(
                node("start", "start", "Start", "", "", Map.of()),
                node("g", "guard", "guard", "", "node_99.output.x == 'y'", Map.of()),
                node("end", "end", "Done", "", "", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("node_99")), result.errors().toString());
    }

    @Test
    void acceptsStateReferenceInTitle() {
        WorkflowDefinition def = def(
                node("start", "start", "Start", "", "", Map.of()),
                node("node_1", "llm_eval", "Status: ${state.lastStatus}", "", "", Map.of()),
                node("end", "end", "Done", "", "", Map.of()));
        GraphCompiler.ValidationResult result = GraphCompiler.validate(def);
        assertTrue(result.valid(), () -> String.join("; ", result.errors()));
    }
}
