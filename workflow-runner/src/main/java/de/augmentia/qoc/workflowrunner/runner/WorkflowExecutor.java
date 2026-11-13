package de.augmentia.qoc.workflowrunner.runner;

import de.augmentia.oc.engine.core.model.EngineResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Principal;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowJson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Executes a ReactFlow-based workflow definition.
 * Ported from agentflow's DeclarativeAgentWorkflow, adapted for ReactFlow format (edges instead of dependsOn).
 *
 * <p>Agent names are prefixed with {@code wf:Name:Version} to avoid conflicts
 * with QOC-internal agents.</p>
 *
 * <p>Execution state lives in the engine journal. The workflow itself is
 * persisted as a specification plus an immutable snapshot of the expanded
 * graph; a retry therefore replays exactly the graph that was started.</p>
 */
public class WorkflowExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final QocEngine engine;
    private final WorkflowDefinitionStore store;
    private final McpServerManager mcpManager;
    private final de.augmentia.qoc.workflowrunner.store.AgentStorePort agentStore;
    private final int maxNodeRepetitions;

    public WorkflowExecutor(QocEngine engine, WorkflowDefinitionStore store) {
        this(engine, store, null, null);
    }

    public WorkflowExecutor(QocEngine engine, WorkflowDefinitionStore store, McpServerManager mcpManager) {
        this(engine, store, mcpManager, null);
    }

    public WorkflowExecutor(QocEngine engine, WorkflowDefinitionStore store,
                            McpServerManager mcpManager,
                            de.augmentia.qoc.workflowrunner.store.AgentStorePort agentStore) {
        this(engine, store, mcpManager, agentStore, 5);
    }

    public WorkflowExecutor(QocEngine engine, WorkflowDefinitionStore store,
                            McpServerManager mcpManager,
                            de.augmentia.qoc.workflowrunner.store.AgentStorePort agentStore,
                            int maxNodeRepetitions) {
        this.engine = engine;
        this.store = store;
        this.mcpManager = mcpManager;
        this.agentStore = agentStore;
        this.maxNodeRepetitions = Math.max(1, maxNodeRepetitions);
    }

    /**
     * Executes a workflow and returns the result text.
     *
     * @deprecated use {@link #executeRun(WorkflowDefinition, String)} when the
     *             caller needs the run/session ID for status or resume.
     */
    @Deprecated
    public String execute(WorkflowDefinition def, String input) {
        return executeRun(def, input).result();
    }

    /**
     * Starts a workflow run and returns its identity plus the engine result.
     */
    public WorkflowExecution executeRun(WorkflowDefinition def, String input) {
        return executeRun(def, input, de.augmentia.oc.engine.core.model.Principal.anonymous());
    }

    public WorkflowExecution executeRun(WorkflowDefinition def, String input, String user) {
        de.augmentia.oc.engine.core.model.Principal principal =
                (user == null || user.isBlank())
                        ? de.augmentia.oc.engine.core.model.Principal.anonymous()
                        : new de.augmentia.oc.engine.core.model.Principal(user, user, java.util.List.of(), java.util.List.of(), java.util.Map.of(), null);
        return executeRun(def, input, principal);
    }

    public WorkflowExecution executeRun(WorkflowDefinition def, String input, de.augmentia.oc.engine.core.model.Principal principal) {
        if (principal == null) principal = de.augmentia.oc.engine.core.model.Principal.anonymous();
        String workflowId = def.id();
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("Workflow ID must not be blank");
        }

        // Stamp ownerId on the definition if not already set.
        String ownerId = def.ownerId();
        if (ownerId == null || ownerId.isBlank()) {
            ownerId = principal.id();
            def = new WorkflowDefinition(def.id(), def.name(), def.version(), def.status(),
                    def.nodes(), def.edges(), def.agents(), ownerId, def.systemPrompt());
        }

        WorkflowDefinition persisted = store.save(workflowId, def);
        WorkflowDefinition expanded = new SubWorkflowExpander(store).expand(persisted);
        String definitionVersion = WorkflowJson.versionId();
        String contentHash = WorkflowJson.contentHash(expanded);
        store.snapshot(workflowId, definitionVersion, expanded);

        String user = principal.id();
        SessionId session = engine.createSession("workflow:" + workflowId, user, "workspace");
        String runId = session.value();
        JournalWorkflowEventSink sink = new JournalWorkflowEventSink(
                engine.stores().journal(), session, runId, engine.clock(), ownerId);

        String context = input == null ? "" : input;
        WorkflowContext ctx = new WorkflowContext();
        ctx.setState("lastResult", context);
        sink.runStarted(workflowId, definitionVersion, contentHash, context);

        return run(session, runId, expanded, ctx, context, sink, true, principal);
    }

    /**
     * Resumes an unfinished run from the last durable node completion.
     */
    public WorkflowExecution resume(String runId) {
        SessionId session;
        try {
            session = SessionId.parse(runId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid workflow run id: " + runId, e);
        }

        WorkflowRunJournal journal = new WorkflowRunJournal(engine);
        WorkflowRunSnapshot snapshot = journal.load(session)
                .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + runId));
        if (snapshot.active()) {
            throw new IllegalStateException("Workflow run is still active: " + runId);
        }
        if ("COMPLETED".equalsIgnoreCase(snapshot.status())) {
            throw new IllegalStateException("Workflow run is already completed: " + runId);
        }

        WorkflowDefinition definition = loadDefinition(snapshot);
        if (definition == null) {
            throw new IllegalStateException("Workflow definition not found for run: " + runId);
        }

        WorkflowContext ctx = new WorkflowContext();
        ctx.restore(snapshot.nodeOutputs(), snapshot.workflowState(), snapshot.currentNodeId(),
                snapshot.input());

        // A crashed process leaves a claim behind. It is released here because
        // the journal proved that no coordinator is active for this session.
        engine.stores().claims().release(session);

        String resumeOwner = snapshot == null ? "" : "";
        try {
            de.augmentia.oc.engine.core.model.SessionInfo info = engine.session(session).orElse(null);
            if (info != null && info.ownerId() != null) resumeOwner = info.ownerId();
        } catch (RuntimeException ignored) {
        }
        JournalWorkflowEventSink sink = new JournalWorkflowEventSink(
                engine.stores().journal(), session, runId, engine.clock(), resumeOwner);
        sink.runResumed(snapshot.resumeCount() + 1);

        LOG.info("Resuming workflow run {} at node {} (attempt {})",
                runId, snapshot.currentNodeId(), snapshot.resumeCount() + 1);
        de.augmentia.oc.engine.core.model.Principal resumePrincipal =
                de.augmentia.oc.engine.core.model.Principal.anonymous();
        try {
            de.augmentia.oc.engine.core.model.SessionInfo info = engine.session(session).orElse(null);
            if (info != null && info.ownerId() != null && !info.ownerId().isBlank()) {
                resumePrincipal = new de.augmentia.oc.engine.core.model.Principal(
                        info.ownerId(), info.ownerId(), java.util.List.of(), java.util.List.of(),
                        java.util.Map.of(), null);
            }
        } catch (RuntimeException ignored) {
        }
        return run(session, runId, definition, ctx, snapshot.input(), sink, false, resumePrincipal);
    }

    private WorkflowExecution run(SessionId session, String runId, WorkflowDefinition definition,
                                  WorkflowContext ctx, String input,
                                  WorkflowEventSink sink, boolean storePrompt) {
        return run(session, runId, definition, ctx, input, sink, storePrompt, Principal.anonymous());
    }

    private WorkflowExecution run(SessionId session, String runId, WorkflowDefinition definition,
                                  WorkflowContext ctx, String input,
                                  WorkflowEventSink sink, boolean storePrompt,
                                  de.augmentia.oc.engine.core.model.Principal principal) {
        if (principal == null) principal = de.augmentia.oc.engine.core.model.Principal.anonymous();
        ctx.setInitialPrompt(input == null ? "" : input);
        NodeDispatcher dispatcher = new DefaultNodeDispatcher(engine, store, mcpManager, agentStore, principal, session.value());
        WorkflowStepProviderImpl provider = new WorkflowStepProviderImpl(definition, dispatcher, sink, maxNodeRepetitions);

        try {
            EngineResult result = engine.runPrompt(
                    session, "workflow:" + definition.id(), input,
                    workflowConfig(definition), storePrompt, provider, ctx, principal);

            if (result.failed()) {
                sink.runFailed(new SessionError.Unknown("workflow",
                        result.errorMessage() == null ? "workflow failed" : result.errorMessage()));
                return new WorkflowExecution(runId, session.value(), result.text(), result.finish(),
                        true, false, result.errorMessage());
            }
            if (result.paused()) {
                sink.runFinished("PAUSED");
                return new WorkflowExecution(runId, session.value(), result.text(), "PAUSED",
                        false, true, null, result.approvalId(), "");
            }
            sink.runFinished("COMPLETED");
            return new WorkflowExecution(runId, session.value(), result.text(), result.finish(),
                    false, false, null);
        } catch (RuntimeException e) {
            sink.runFailed(new SessionError.Unknown("workflow", e.getMessage()));
            return new WorkflowExecution(runId, session.value(), "", "FAILED",
                    true, false, e.getMessage());
        }
    }

    private WorkflowDefinition loadDefinition(WorkflowRunSnapshot snapshot) {
        WorkflowDefinition definition = store.version(snapshot.workflowId(), snapshot.definitionVersion());
        if (definition == null) {
            definition = store.findByHash(snapshot.workflowId(), snapshot.contentHash());
        }
        if (definition == null) {
            definition = store.get(snapshot.workflowId());
        }
        return definition;
    }

    private QocEngine.AgentConfig workflowConfig(WorkflowDefinition definition) {
        String name = "workflow:" + definition.id();
        return new QocEngine.AgentConfig(name, null, 0.3, 4096, 1.0, null,
                List.of(), List.of(), List.of(), List.of(), false, Option.none());
    }
}
