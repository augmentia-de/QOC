package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeDataContractTest {

    private static final Map<String, Object> CONTRACT = Map.of(
            "type", "object",
            "properties", Map.of(
                    "status", Map.of("type", "string", "enum", List.of("OK", "FAIL")),
                    "count", Map.of("type", "integer", "minimum", 0)
            ),
            "required", List.of("status", "count"),
            "additionalProperties", false
    );

    @Test
    void validOutput_passesContract() {
        NodeContractValidator.validate(CONTRACT, Map.of("status", "OK", "count", 5));
        // no exception
    }

    @Test
    void missingRequiredField_fails() {
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> NodeContractValidator.validate(CONTRACT, Map.of("status", "OK")));
        assertTrue(e.path().contains("count"));
    }

    @Test
    void wrongType_fails() {
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> NodeContractValidator.validate(CONTRACT, Map.of("status", "OK", "count", "five")));
        assertTrue(e.path().contains("count"));
    }

    @Test
    void additionalProperty_rejected() {
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> NodeContractValidator.validate(CONTRACT, Map.of("status", "OK", "count", 5, "extra", true)));
        assertTrue(e.path().contains("extra"));
    }

    @Test
    void invalidEnumValue_fails() {
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> NodeContractValidator.validate(CONTRACT, Map.of("status", "MAYBE", "count", 5)));
        assertTrue(e.path().contains("status"));
    }

    @Test
    void noContract_passesThrough() {
        // null/empty contract -> no validation
        NodeContractValidator.validate(null, Map.of("anything", "goes"));
        NodeContractValidator.validate(Map.of(), Map.of("anything", "goes"));
    }

    @Test
    void contextRefs_filtersInput() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("produce", Map.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5));
        String text = ContextAssembler.assemble(minimalDef(), ctx, ContextAssembler.Policy.CUSTOM,
                "task", List.of("produce"));
        assertTrue(text.contains("\"a\""));
        // field-level ref filters to one value
        String fieldText = ContextAssembler.assemble(minimalDef(), ctx, ContextAssembler.Policy.CUSTOM,
                "task", List.of("produce.a"));
        assertTrue(fieldText.contains("produce.a"));
        assertTrue(fieldText.contains("1"));
    }

    @Test
    void contextRefs_empty_usesLastResult() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setInitialPrompt("start");
        ctx.setNodeOutput("n1", "out");
        String text = ContextAssembler.assemble(minimalDef(), ctx, ContextAssembler.Policy.CUSTOM,
                "task", List.of());
        // empty refs -> nothing rendered in CUSTOM (fallback handled by dispatcher)
        assertNotNull(text);
    }

    @Test
    void contextRefs_unknownRef_throws() {
        // ContextRefResolver.findProblems reports unknown node
        List<String> problems = ContextRefResolver.findProblems("nonexistent", java.util.Set.of("produce"));
        assertFalse(problems.isEmpty());
        assertTrue(problems.get(0).contains("unknown node"));
    }

    @Test
    void templateResolve_typeCheck() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("produce", Map.of("count", 42));
        Object resolved = TemplateResolver.resolveToObject("${produce.output.count}", ctx);
        assertEquals(42L, resolved);
    }

    @Test
    void templateResolve_typeMismatch_throws() {
        // resolveNestedProperty returns null for non-map intermediate
        WorkflowContext ctx = new WorkflowContext();
        ctx.setNodeOutput("produce", "not-a-map");
        Object resolved = TemplateResolver.resolveToObject("${produce.output.count}", ctx);
        assertNull(resolved);
    }

    private static WorkflowDefinition minimalDef() {
        WorkflowDefinition.Node n1 = new WorkflowDefinition.Node("n1", "agent",
                new WorkflowDefinition.Point(0, 0),
                new WorkflowDefinition.NodeData("Agent", "a", false, "human", 3, "", List.of(),
                        "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        WorkflowDefinition.Node n2 = new WorkflowDefinition.Node("n2", "agent",
                new WorkflowDefinition.Point(10, 0),
                new WorkflowDefinition.NodeData("Agent2", "a", false, "human", 3, "", List.of(),
                        "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        return new WorkflowDefinition("wf", "wf", "1.0", "draft", List.of(n1, n2), List.of(),
                List.of(), "owner", "");
    }
}
