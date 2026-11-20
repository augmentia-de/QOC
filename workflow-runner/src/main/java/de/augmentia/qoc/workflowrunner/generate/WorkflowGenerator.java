package de.augmentia.qoc.workflowrunner.generate;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.AgentStorePort;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AgentStorePort agentStore;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public WorkflowGenerator(Function<String, String> llmCaller,
                            WorkflowDefinitionStore store,
                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this(llmCaller, store, null, mapper);
    }

    public WorkflowGenerator(Function<String, String> llmCaller,
                            WorkflowDefinitionStore store,
                            AgentStorePort agentStore,
                            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (llmCaller == null || store == null || mapper == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.llmCaller = llmCaller;
        this.store = store;
        this.agentStore = agentStore;
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

        // Persist generated agents to the global agent store (DB) so they are
        // available as standalone agents (e.g. category "Generated") and can be
        // resolved by ID at execution time even when the workflow is run without
        // its inline agent list. Assign globally unique IDs.
        if (agentStore != null && def.agents() != null && !def.agents().isEmpty()) {
            String wfId = def.id();
            Map<String, String> idMap = new LinkedHashMap<>();
            List<WorkflowDefinition.Agent> newAgents = new ArrayList<>();
            for (WorkflowDefinition.Agent a : def.agents()) {
                String newId = "gen-" + wfId + "-" + a.id();
                // Ensure uniqueness against the store.
                int suffix = 2;
                String candidate = newId;
                while (agentStore.get(candidate) != null) {
                    candidate = newId + "-" + suffix++;
                }
                newId = candidate;
                WorkflowDefinition.Agent persisted = new WorkflowDefinition.Agent(
                        newId,
                        a.name().isBlank() ? a.id() : a.name(),
                        a.version(),
                        "Generated",
                        a.model(),
                        a.temperature(),
                        a.maxTokens(),
                        a.topP(),
                        a.tools(),
                        a.guardrailsInput(),
                        a.guardrailsOutput(),
                        a.hooks(),
                        a.jsonOutput(),
                        a.active(),
                        a.systemPrompt(),
                        a.responseSchema(),
                        a.ownerId(),
                        a.rolesAllowed(),
                        a.mode(),
                        a.skills(),
                        a.maxTurns(),
                        a.maxCost(),
                        a.requiresHITL(),
                        a.permissions() == null ? java.util.List.of() : a.permissions());
                agentStore.save(newId, persisted);
                idMap.put(a.id(), newId);
                newAgents.add(persisted);
            }
            // Rebuild nodes with updated agentRefs.
            List<WorkflowDefinition.Node> newNodes = new ArrayList<>();
            for (WorkflowDefinition.Node n : def.nodes()) {
                if (n.data() != null && n.data().agentRef() != null
                        && idMap.containsKey(n.data().agentRef())) {
                    WorkflowDefinition.NodeData d = n.data();
                    WorkflowDefinition.NodeData nd = new WorkflowDefinition.NodeData(
                            d.title(), idMap.get(d.agentRef()), d.requiresHumanApproval(),
                            d.assignee(), d.maxIterations(), d.exitCondition(), d.bodySteps(),
                            d.joinType(), d.joinN(), d.branches(), d.defaultTarget(),
                            d.workflowRef(), d.responseSchema(), d.mcpServer(), d.mcpTool(),
                            d.mcpArgs(), d.jsonOutput(), d.outputContract(), d.contextRefs(),
                            d.strict(), d.verifyOnStop());
                    newNodes.add(new WorkflowDefinition.Node(n.id(), n.type(), n.position(), nd));
                } else {
                    newNodes.add(n);
                }
            }
            def = new WorkflowDefinition(def.id(), def.name(), def.version(), def.status(),
                    newNodes, def.edges(), newAgents, def.ownerId(), def.systemPrompt());
        }
        // Validate with GraphCompiler (best-effort; throws on structural issues).
        var validation = GraphCompiler.validate(def);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Generated workflow invalid: " + validation.errors());
        }
        // Contract validation (warnings only, non-blocking).
        java.util.List<String> contractWarnings = WorkflowContractValidator.validate(def);
        // LLM-based semantic contract review (best-effort).
        java.util.List<String> llmWarnings = WorkflowContractValidator.validateWithLlm(def, llmCaller);
        if (!llmWarnings.isEmpty()) {
            contractWarnings = new java.util.ArrayList<>(contractWarnings);
            contractWarnings.addAll(llmWarnings);
        }
        if (!contractWarnings.isEmpty()) {
            System.getLogger(WorkflowGenerator.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "Generated workflow contract warnings: " + contractWarnings);
        }
        // Persist.
        String id = def.id();
        if (id == null || id.isBlank()) {
            id = "wf-" + UUID.randomUUID().toString().substring(0, 8);
        }
        return store.save(id, def);
    }
}
