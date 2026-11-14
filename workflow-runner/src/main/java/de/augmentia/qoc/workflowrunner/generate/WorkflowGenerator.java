package de.augmentia.qoc.workflowrunner.generate;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;
import de.augmentia.qoc.workflowrunner.validation.GraphCompiler;

import java.util.UUID;
import java.util.function.Function;

/**
 * Orchestrates workflow generation from a free-text prompt:
 *   prompt -> LLM -> stripFences -> parseSteps -> validateSteps -> build -> store.save
 */
public class WorkflowGenerator {

    private static final String SYSTEM_PROMPT = """
            You are a workflow architect. Create a workflow as JSON with this schema:
            {
              "workflowName": "short name",
              "description": "one sentence",
              "steps": [
                {
                  "agentRole": "analyzer",
                  "instruction": "what this step does",
                  "tools": ["read", "grep"],
                  "dependsOn": [],
                  "timeoutSeconds": 120,
                  "systemPrompt": "optional system prompt for the agent"
                }
              ]
            }
            Rules:
            - Each step's agentRole must be unique.
            - dependsOn may only reference existing agentRoles.
            - No cycles in the dependency graph.
            - Respond with ONLY the JSON object, no markdown, no prose.
            """;

    private final Function<String, String> llmCaller;
    private final WorkflowDefinitionStore store;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public WorkflowGenerator(Function<String, String> llmCaller,
                            WorkflowDefinitionStore store,
                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (llmCaller == null || store == null || mapper == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.llmCaller = llmCaller;
        this.store = store;
        this.mapper = mapper;
    }

    public WorkflowDefinition generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        String fullPrompt = SYSTEM_PROMPT + "\n\nUser request: " + prompt.trim();
        String raw = llmCaller.apply(fullPrompt);
        String json = StripFences.strip(raw);
        WorkflowPlanParser.WorkbookResult result = WorkflowPlanParser.parseSteps(mapper, json);
        WorkflowDefinition def = WorkflowBuilder.build(result);
        // Validate with GraphCompiler (best-effort; throws on structural issues).
        var validation = GraphCompiler.validate(def);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Generated workflow invalid: " + validation.errors());
        }
        // Persist.
        String id = def.id();
        if (id == null || id.isBlank()) {
            id = "wf-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return store.save(id, def);
    }
}
