package de.augmentia.qoc.workflowrunner.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowPlanParserTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesValidJson() {
        String json = "{\"workflowName\":\"w\",\"steps\":[{\"agentRole\":\"a\",\"instruction\":\"do\",\"tools\":[\"read\"],\"dependsOn\":[],\"timeoutSeconds\":60}]}";
        WorkflowPlanParser.WorkbookResult r = WorkflowPlanParser.parseSteps(MAPPER, json);
        assertEquals("w", r.workflowName());
        assertEquals(1, r.steps().size());
        assertEquals("a", r.steps().get(0).agentRole());
    }

    @Test
    void missingStepsThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorkflowPlanParser.parseSteps(MAPPER, "{\"x\":1}"));
        assertTrue(e.getMessage().contains("steps"));
    }

    @Test
    void duplicateRoleThrows() {
        String json = "{\"steps\":[{\"agentRole\":\"a\"},{\"agentRole\":\"a\"}]}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorkflowPlanParser.parseSteps(MAPPER, json));
        assertTrue(e.getMessage().contains("Duplicate"));
    }

    @Test
    void unknownDependencyThrows() {
        String json = "{\"steps\":[{\"agentRole\":\"a\",\"dependsOn\":[\"b\"]}]}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorkflowPlanParser.parseSteps(MAPPER, json));
        assertTrue(e.getMessage().contains("non-existent"));
    }

    @Test
    void cycleThrows() {
        String json = "{\"steps\":[{\"agentRole\":\"a\",\"dependsOn\":[\"b\"]},{\"agentRole\":\"b\",\"dependsOn\":[\"a\"]}]}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorkflowPlanParser.parseSteps(MAPPER, json));
        assertTrue(e.getMessage().contains("Cycle"));
    }

    @Test
    void emptyStepsThrows() {
        String json = "{\"steps\":[]}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorkflowPlanParser.parseSteps(MAPPER, json));
        assertTrue(e.getMessage().contains("0 steps"));
    }

    @Test
    void blankAgentRoleThrows() {
        String json = "{\"steps\":[{\"agentRole\":\"\"}]}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorkflowPlanParser.parseSteps(MAPPER, json));
        assertTrue(e.getMessage().contains("agentRole"));
    }

    @Test
    void invalidJsonThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> WorkflowPlanParser.parseSteps(MAPPER, "{not json"));
        assertTrue(e.getMessage().contains("Invalid JSON"));
    }
}
