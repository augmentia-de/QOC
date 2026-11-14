package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Distinguishes two situations that are indistinguishable from the context
 * alone, which is why strict resolution can take the definition's node IDs:
 *
 * <ul>
 *   <li>the template names a node that does not exist — an authoring mistake that
 *       can never resolve, so it must fail loudly;</li>
 *   <li>the node exists but produced no output (branch not taken, or it runs
 *       later) — a runtime condition, so it degrades to {@code null}.</li>
 * </ul>
 */
class StrictResolutionNodeSetTest {

    private static final Set<String> NODES = Set.of("start", "node_1", "late-node", "end");

    @Test
    void unknownNodeThrowsWhenNodeIdsAreKnown() {
        WorkflowContext ctx = new WorkflowContext();
        UnresolvedPlaceholderException error = assertThrows(UnresolvedPlaceholderException.class,
                () -> TemplateResolver.resolveStrict("${node_99.output}", ctx, NODES));
        assertEquals("Cannot resolve '${node_99.output}': no such node in this workflow",
                error.getMessage());
    }

    @Test
    void existingNodeWithoutOutputDoesNotThrow() {
        // e.g. a branch that was not taken, or a node further downstream.
        WorkflowContext ctx = new WorkflowContext();
        assertEquals("null",
                TemplateResolver.resolveStrict("${late-node.output}", ctx, NODES));
    }

    @Test
    void existingNodeWithOutputResolves() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("node_1", Map.of("name", "Bob"));
        assertEquals("Bob", TemplateResolver.resolveStrict("${node_1.output.name}", ctx, NODES));
    }

    @Test
    void withoutNodeIdsEveryMissingOutputThrows() {
        // Strict without a definition falls back to the conservative rule.
        WorkflowContext ctx = new WorkflowContext();
        assertThrows(UnresolvedPlaceholderException.class,
                () -> TemplateResolver.resolveStrict("${late-node.output}", ctx));
    }

    @Test
    void lenientResolutionNeverThrows() {
        WorkflowContext ctx = new WorkflowContext();
        assertEquals("null", TemplateResolver.resolve("${node_99.output}", ctx));
    }

    @Test
    void stateReferenceDoesNotThrow() {
        WorkflowContext ctx = new WorkflowContext();
        assertEquals("null", TemplateResolver.resolveStrict("${state.absent}", ctx, NODES));
    }

    @Test
    void lastResultReferenceDoesNotThrow() {
        WorkflowContext ctx = new WorkflowContext();
        assertEquals("null", TemplateResolver.resolveStrict("${lastResult}", ctx, NODES));
    }

    @Test
    void templatesWithoutPlaceholdersAreReturnedAsIs() {
        WorkflowContext ctx = new WorkflowContext();
        assertEquals("plain", TemplateResolver.resolveStrict("plain", ctx, NODES));
        assertEquals("{{lastResult}}", TemplateResolver.resolveStrict("{{lastResult}}", ctx, NODES));
    }

    @Test
    void multiplePlaceholdersReportTheFirstUnknown() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("node_1", "ok");
        UnresolvedPlaceholderException error = assertThrows(UnresolvedPlaceholderException.class,
                () -> TemplateResolver.resolveStrict("${node_1.output} ${ghost.output}", ctx, NODES));
        assertEquals("Cannot resolve '${ghost.output}': no such node in this workflow",
                error.getMessage());
    }

    @Test
    void listOfAllProblemsIsAvailableForValidation() {
        assertEquals(List.of(),
                TemplateResolver.findProblems("${node_1.output}", NODES, true));
    }
}
