package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compile-time template checks and strict resolution.
 *
 * <p>The point of these checks is to catch at save/run time what used to fail
 * silently at runtime: unsupported syntax, unknown node references and
 * malformed placeholders.</p>
 */
class TemplateValidationTest {

    private static final Set<String> NODES = Set.of("start", "node_1", "erp-prices", "end");

    // --- findProblems -----------------------------------------------------

    @Test
    void acceptsValidPlaceholders() {
        assertEquals(List.of(), TemplateResolver.findProblems("${lastResult}", NODES, true));
        assertEquals(List.of(), TemplateResolver.findProblems("${node_1.output.name}", NODES, true));
        assertEquals(List.of(), TemplateResolver.findProblems("${erp-prices.output}", NODES, true));
        assertEquals(List.of(), TemplateResolver.findProblems("${state.lastStatus}", NODES, true));
    }

    @Test
    void acceptsPlainText() {
        assertEquals(List.of(), TemplateResolver.findProblems("no placeholders", NODES, true));
        assertEquals(List.of(), TemplateResolver.findProblems(null, NODES, true));
    }

    @Test
    void rejectsMustacheSyntax() {
        // This is the exact defect the original investigation ran into.
        List<String> problems = TemplateResolver.findProblems("Evaluate: {{lastResult}}", NODES, true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("{{lastResult}}"), problems.get(0));
        assertTrue(problems.get(0).contains("${...}"), problems.get(0));
    }

    @Test
    void rejectsUnknownNode() {
        List<String> problems = TemplateResolver.findProblems("${node_99.output.name}", NODES, true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("node_99"), problems.get(0));
    }

    @Test
    void rejectsMalformedPlaceholder() {
        List<String> problems = TemplateResolver.findProblems("${node 1.output}", NODES, true);
        assertTrue(problems.stream().anyMatch(p -> p.contains("malformed")), problems.toString());
    }

    @Test
    void rejectsStateReferenceWhenNotAllowed() {
        List<String> problems = TemplateResolver.findProblems("${state.x}", NODES, false);
        assertTrue(problems.stream().anyMatch(p -> p.contains("not allowed")), problems.toString());
    }

    @Test
    void reportsMultipleProblems() {
        List<String> problems = TemplateResolver.findProblems(
                "{{a}} and ${node_99.output}", NODES, true);
        assertEquals(2, problems.size(), problems.toString());
    }

    // --- findConditionProblems -------------------------------------------

    @Test
    void acceptsConditionOnKnownNode() {
        assertEquals(List.of(),
                TemplateResolver.findConditionProblems("node_1.output.classification == 'X'", NODES));
        assertEquals(List.of(),
                TemplateResolver.findConditionProblems("erp-prices.output.total == '1'", NODES));
    }

    @Test
    void rejectsConditionOnUnknownNode() {
        List<String> problems = TemplateResolver.findConditionProblems("node_99.output.x == 'y'", NODES);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("node_99"), problems.get(0));
    }

    @Test
    void ignoresLegacyAndStateConditions() {
        assertEquals(List.of(), TemplateResolver.findConditionProblems("contains:LOW_MARGIN", NODES));
        assertEquals(List.of(), TemplateResolver.findConditionProblems("status == 'SUCCESS'", NODES));
        assertEquals(List.of(), TemplateResolver.findConditionProblems("lastStatus == 'OK'", NODES));
        assertEquals(List.of(), TemplateResolver.findConditionProblems("", NODES));
    }

    // --- strict resolution ------------------------------------------------

    @Test
    void lenientResolutionStillYieldsNullLiteral() {
        // Historical behaviour, kept for existing definitions.
        WorkflowContext ctx = new WorkflowContext();
        assertEquals("x null y", TemplateResolver.resolve("x ${node_99.output} y", ctx));
    }

    @Test
    void strictResolutionThrowsForUnknownNode() {
        WorkflowContext ctx = new WorkflowContext();
        UnresolvedPlaceholderException error = assertThrows(UnresolvedPlaceholderException.class,
                () -> TemplateResolver.resolveStrict("x ${node_99.output} y", ctx));
        assertTrue(error.getMessage().contains("node_99"), error.getMessage());
    }

    @Test
    void strictResolutionResolvesKnownValues() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("node_1", Map.of("name", "Bob"));
        assertEquals("hi Bob", TemplateResolver.resolveStrict("hi ${node_1.output.name}", ctx));
    }

    @Test
    void strictResolutionStillAllowsExplicitNull() {
        // A node that exists but has no value must not throw.
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("node_1", Map.of("missing", "x"));
        assertEquals("null", TemplateResolver.resolveStrict("${node_1.output.absent}", ctx));
    }
}
