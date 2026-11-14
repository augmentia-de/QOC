package de.augmentia.qoc.workflowrunner.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowDefinitionJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Old JSON (before introduction of systemPrompt) must still be loadable. */
    @Test
    void oldJsonWithoutSystemPromptDefaultsToEmptyString() throws Exception {
        String json = """
                {
                  "id": "wf1",
                  "name": "Old Workflow",
                  "version": "1.0.0",
                  "status": "draft",
                  "nodes": [],
                  "edges": [],
                  "agents": [],
                  "ownerId": "u1"
                }
                """;
        WorkflowDefinition def = MAPPER.readValue(json, WorkflowDefinition.class);
        assertEquals("", def.systemPrompt());
        assertEquals("u1", def.ownerId());
    }

    /** New JSON with systemPrompt is read; unknown fields are ignored. */
    @Test
    void newJsonWithSystemPromptIsRead() throws Exception {
        String json = """
                {
                  "id": "wf2",
                  "name": "New Workflow",
                  "version": "1.0.0",
                  "status": "draft",
                  "nodes": [],
                  "edges": [],
                  "agents": [],
                  "ownerId": "u1",
                  "systemPrompt": "shared prompt",
                  "someFutureField": true
                }
                """;
        WorkflowDefinition def = MAPPER.readValue(json, WorkflowDefinition.class);
        assertEquals("shared prompt", def.systemPrompt());
    }

    /** Null systemPrompt wird im Compact-Constructor zu "". */
    @Test
    void nullSystemPromptBecomesEmpty() {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf3", "W", "1.0.0", "draft", List.of(), List.of(), List.of(), "u", null);
        assertEquals("", def.systemPrompt());
    }

    /** 8-arg constructor stays backwards compatible and sets "". */
    @Test
    void legacyConstructorSetsEmptySystemPrompt() {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf4", "W", "1.0.0", "draft", List.of(), List.of(), List.of(), "u");
        assertEquals("", def.systemPrompt());
    }

    /** Serialisierung schreibt systemPrompt mit (Roundtrip). */
    @Test
    void roundTripKeepsSystemPrompt() throws Exception {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf5", "W", "1.0.0", "draft", List.of(), List.of(), List.of(), "u", "sp");
        String json = MAPPER.writeValueAsString(def);
        assertTrue(json.contains("systemPrompt"));
        WorkflowDefinition back = MAPPER.readValue(json, WorkflowDefinition.class);
        assertEquals("sp", back.systemPrompt());
    }

    @Test
    void nodeDataStaysCompatible() throws Exception {
        String json = """
                {
                  "id": "n1",
                  "type": "context_agent_executor",
                  "position": {"x": 0, "y": 0},
                  "data": {"title": "Context Agent", "agentRef": "a1"}
                }
                """;
        WorkflowDefinition.Node node = MAPPER.readValue(json, WorkflowDefinition.Node.class);
        assertEquals("context_agent_executor", node.type());
        assertEquals("a1", node.data().agentRef());
        assertEquals("Context Agent", node.data().title() == null ? "" : node.data().title());
        assertNotNull(node.data());
        assertEquals(Map.of(), node.data().mcpArgs());
    }
}
