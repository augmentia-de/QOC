package de.augmentia.qoc.workflowrunner.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.InMemoryWorkflowStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID_JSON = """
            {
              "workflowName": "test-wf",
              "description": "a test workflow",
              "steps": [
                { "agentRole": "analyzer", "instruction": "analyze input", "tools": ["read"], "dependsOn": [], "timeoutSeconds": 60 },
                { "agentRole": "summarizer", "instruction": "summarize", "tools": [], "dependsOn": ["analyzer"], "timeoutSeconds": 60 }
              ]
            }
            """;

    @Test
    void generatesAndPersistsWorkflow() {
        WorkflowDefinitionStore store = new InMemoryWorkflowStore();
        WorkflowGenerator generator = new WorkflowGenerator(prompt -> VALID_JSON, store, MAPPER);

        WorkflowDefinition def = generator.generate("analyze and summarize");

        assertNotNull(def);
        assertEquals("test-wf", def.name());
        assertEquals(4, def.nodes().size());
        assertEquals(3, def.edges().size());
        assertEquals(2, def.agents().size());
        // Persisted
        WorkflowDefinition loaded = store.get(def.id());
        assertNotNull(loaded);
        assertEquals(def.id(), loaded.id());
        assertEquals("test-wf", loaded.name());
    }

    @Test
    void rejectsBlankPrompt() {
        WorkflowDefinitionStore store = new InMemoryWorkflowStore();
        WorkflowGenerator generator = new WorkflowGenerator(prompt -> VALID_JSON, store, MAPPER);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> generator.generate("   "));
    }

    @Test
    void rejectsInvalidGeneratedWorkflow() {
        WorkflowDefinitionStore store = new InMemoryWorkflowStore();
        // Missing dependsOn target → GraphCompiler validation fails
        String badJson = """
                { "workflowName": "bad", "steps": [
                  { "agentRole": "a", "instruction": "x", "dependsOn": ["nonexistent"] }
                ] }
                """;
        WorkflowGenerator generator = new WorkflowGenerator(prompt -> badJson, store, MAPPER);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> generator.generate("bad"));
    }

    @Test
    void stripsFencesFromLlmOutput() {
        WorkflowDefinitionStore store = new InMemoryWorkflowStore();
        String fenced = "```json\n" + VALID_JSON + "\n```";
        WorkflowGenerator generator = new WorkflowGenerator(prompt -> fenced, store, MAPPER);
        WorkflowDefinition def = generator.generate("fenced");
        assertEquals("test-wf", def.name());
    }
}
