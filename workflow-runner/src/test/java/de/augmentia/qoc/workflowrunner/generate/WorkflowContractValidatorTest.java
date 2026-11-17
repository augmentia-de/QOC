package de.augmentia.qoc.workflowrunner.generate;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowContractValidatorTest {

    private static WorkflowDefinition defWithTool(String tool, boolean hasContract) {
        WorkflowDefinition.NodeData data = new WorkflowDefinition.NodeData(
                "Node", "a1", false, "human", 3, "", List.of(), "all", null, List.of(),
                "", "", null, "", tool, Map.of(), null,
                hasContract ? Map.of("type", "object") : null, List.of(), false, List.of());
        WorkflowDefinition.Node n = new WorkflowDefinition.Node("n1", "agent",
                new WorkflowDefinition.Point(0, 0), data);
        WorkflowDefinition.Agent a = new WorkflowDefinition.Agent("a1", "A", "1.0.0", "", "m", 0.3, 4096,
                1.0, List.of(), List.of(), List.of(), List.of(), false, true,
                "system prompt", null, "", List.of("*"), "all", List.of());
        return new WorkflowDefinition("wf", "W", "1.0.0", "draft", List.of(n), List.of(), List.of(a), "", "");
    }

    @Test
    void validToolName_noNamingWarning() {
        WorkflowDefinition def = defWithTool("support_tickets_search", true);
        List<String> w = WorkflowContractValidator.validate(def);
        assertTrue(w.stream().noneMatch(s -> s.contains("domain_resource_action")));
    }

    @Test
    void invalidToolName_generatesWarning() {
        WorkflowDefinition def = defWithTool("doStuff", true);
        List<String> w = WorkflowContractValidator.validate(def);
        assertTrue(w.stream().anyMatch(s -> s.contains("domain_resource_action")));
    }

    @Test
    void missingOutputContract_generatesWarning() {
        WorkflowDefinition def = defWithTool("support_tickets_search", false);
        List<String> w = WorkflowContractValidator.validate(def);
        assertTrue(w.stream().anyMatch(s -> s.contains("no outputContract")));
    }

    @Test
    void missingSystemPrompt_generatesWarning() {
        WorkflowDefinition def = defWithTool("support_tickets_search", true);
        // agent with blank systemPrompt
        WorkflowDefinition.Agent a = new WorkflowDefinition.Agent("a1", "A", "1.0.0", "", "m", 0.3, 4096,
                1.0, List.of(), List.of(), List.of(), List.of(), false, true,
                "", null, "", List.of("*"), "all", List.of());
        def = new WorkflowDefinition("wf", "W", "1.0.0", "draft", def.nodes(), List.of(), List.of(a), "", "");
        List<String> w = WorkflowContractValidator.validate(def);
        assertTrue(w.stream().anyMatch(s -> s.contains("no systemPrompt")));
    }

    @Test
    void validateWithLlm_parsesArray() {
        WorkflowDefinition def = defWithTool("support_tickets_search", true);
        List<String> w = WorkflowContractValidator.validateWithLlm(def, prompt -> "[\"warn1\", \"warn2\"]");
        assertEquals(2, w.size());
        assertTrue(w.contains("warn1"));
    }

    @Test
    void validateWithLlm_nullCaller_returnsEmpty() {
        WorkflowDefinition def = defWithTool("support_tickets_search", true);
        List<String> w = WorkflowContractValidator.validateWithLlm(def, null);
        assertTrue(w.isEmpty());
    }

    @Test
    void nullDef_returnsWarning() {
        List<String> w = WorkflowContractValidator.validate(null);
        assertFalse(w.isEmpty());
    }
}
