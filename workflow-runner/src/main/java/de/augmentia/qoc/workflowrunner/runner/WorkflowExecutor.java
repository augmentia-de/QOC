package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.model.EngineResult;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.WorkflowStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Executes a ReactFlow-based workflow definition.
 * Ported from agentflow's DeclarativeAgentWorkflow, adapted for ReactFlow format (edges instead of dependsOn).
 *
 * Agent names are prefixed with "wf:Name:Version" to avoid conflicts with QOC-internal agents.
 * Agent config is passed to QocEngine.runPrompt(name, prompt, config).
 *
 * Uses WorkflowContext for state management (structured outputs, global state).
 */
public class WorkflowExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final QocEngine engine;
    private final WorkflowStore store;

    public WorkflowExecutor(QocEngine engine, WorkflowStore store) {
        this.engine = engine;
        this.store = store;
    }

    /**
     * Executes a workflow and returns the result.
     */
    public String execute(WorkflowDefinition def, String input) {
        LOG.debug("Executing workflow: {} (input: {})", def.id(), input);
        // Expand sub-workflows first
        WorkflowDefinition expanded = new SubWorkflowExpander(store).expand(def);
        LOG.debug("Expanded workflow: {} nodes, {} edges", expanded.nodes().size(), expanded.edges().size());

        WorkflowContext ctx = new WorkflowContext();
        String context = input == null ? "" : input;
        ctx.setState("lastResult", context);

        // Create NodeDispatcher and WorkflowStepProvider
        NodeDispatcher dispatcher = new DefaultNodeDispatcher(engine, store);
        WorkflowStepProviderImpl provider = new WorkflowStepProviderImpl(expanded, dispatcher);

        // Delegate to QocEngine's workflow-mode loop
        LOG.debug("Delegating to QocEngine workflow-mode loop");
        EngineResult result = engine.runPrompt(
                null, "workflow:" + def.id(), context,
                new QocEngine.AgentConfig("workflow:" + def.id(), null, 0.3, 4096, 1.0, null,
                        List.of(), List.of(), List.of(), List.of(), false),
                true, provider, ctx, de.augmentia.oc.engine.core.model.Principal.anonymous());

        LOG.debug("Workflow completed: {}", result.text());
        return result.text();
    }

    /**
     * Creates a prefixed agent name: wf:Name:Version
     */
    private String prefixedName(WorkflowDefinition.Agent a, String fallback) {
        if (a == null) return "wf:" + (fallback == null ? "unknown" : fallback) + ":1.0.0";
        String name = a.name() == null || a.name().isBlank() ? a.id() : a.name();
        String version = a.version() == null || a.version().isBlank() ? "1.0.0" : a.version();
        return "wf:" + name + ":" + version;
    }

    /**
     * Converts a workflow Agent to QocEngine.AgentConfig with prefixed name.
     */
    private QocEngine.AgentConfig toAgentConfig(WorkflowDefinition.Agent a, String prefixedName) {
        if (a == null) {
            return new QocEngine.AgentConfig(prefixedName, null, 0.3, 4096, 1.0, null,
                    List.of(), List.of(), List.of(), List.of(), false);
        }
        return new QocEngine.AgentConfig(
            prefixedName,
            a.model(),
            a.temperature(),
            a.maxTokens(),
            a.topP(),
            a.systemPrompt(),
            a.tools(),
            a.guardrailsInput(),
            a.guardrailsOutput(),
            a.hooks(),
            a.jsonOutput()
        );
    }
}
