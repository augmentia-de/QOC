package de.augmentia.qoc.workflowrunner.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.qoc.workflowrunner.generate.WorkflowPlanParser.WorkbookResult;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowBuilderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildsWorkflowFromResult() {
        String json = "{\"workflowName\":\"w\",\"steps\":["
                + "{\"agentRole\":\"a\",\"instruction\":\"do a\",\"tools\":[\"read\"],\"dependsOn\":[]},"
                + "{\"agentRole\":\"b\",\"instruction\":\"do b\",\"tools\":[],\"dependsOn\":[\"a\"]}"
                + "]}";
        WorkbookResult r = WorkflowPlanParser.parseSteps(MAPPER, json);
        WorkflowDefinition def = WorkflowBuilder.build(r);
        assertEquals("w", def.name());
        assertEquals(4, def.nodes().size());
        assertEquals(3, def.edges().size());
        assertEquals(2, def.agents().size());
        assertEquals("a", def.edges().get(1).source());
        assertEquals("b", def.edges().get(1).target());
    }

    @Test
    void throwsOnNullResult() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowBuilder.build(null));
    }

    @Test
    void singleStepNoEdges() {
        String json = "{\"steps\":[{\"agentRole\":\"solo\"}]}";
        WorkbookResult r = WorkflowPlanParser.parseSteps(MAPPER, json);
        WorkflowDefinition def = WorkflowBuilder.build(r);
        assertEquals(3, def.nodes().size());
        assertEquals(2, def.edges().size());
        assertEquals(1, def.agents().size());
    }
}
