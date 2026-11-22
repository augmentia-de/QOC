package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.wiring.ExtensionPorts;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.EngineResult;
import de.augmentia.oc.engine.core.model.Location;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.SessionStore;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.runtime.execution.Coordinator;
import de.augmentia.oc.engine.runtime.execution.ExecutionRunner;
import de.augmentia.oc.engine.runtime.execution.ExecutionScope;
import de.augmentia.oc.engine.runtime.execution.Recovery;
import de.augmentia.oc.engine.runtime.session.core.SessionService;
import de.augmentia.oc.engine.runtime.callback.EventPublisher;
import de.augmentia.oc.engine.runtime.callback.AgentResolver;
import de.augmentia.oc.engine.runtime.callback.TracerProvider;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Framework-neutral facade for the QOC session engine.
 *
 * <p>Can be instantiated without Quarkus, Spring, or any DI framework.
 * Uses {@link StorageFactory} to select storage backends per port.</p>
 *
 * <p>This is the entry point for embedding QOC as a library (e.g. in AgentFlow).</p>
 */
public final class QocEngine {

/**
 * Configuration for the QOC engine (resilience, turn policy, etc.).
 */
public static record EngineConfig(
        long retryDelay1Seconds,
        long retryDelay2Seconds,
        int retryMaxAttempts,
        boolean preflightEnabled,
        long preflightTtlSeconds,
        long preflightTimeoutSeconds,
        boolean preflightDnsEnabled,
        boolean preflightHttpCheckEnabled,
        int loopDetectionThreshold,
        boolean triageEnabled,
        int triageMaxAttempts,
        boolean webhookEnabled,
        String webhookUrl,
        String webhookFormat,
        long stepTimeoutSeconds,
        int maxTurnToolCalls,
        long maxTurnTokens,
        long maxTurnWallMinutes
) {
    public EngineConfig {
        if (retryDelay1Seconds <= 0) retryDelay1Seconds = 10;
        if (retryDelay2Seconds <= 0) retryDelay2Seconds = 30;
        if (retryMaxAttempts <= 0) retryMaxAttempts = 3;
        if (preflightTtlSeconds <= 0) preflightTtlSeconds = 60;
        if (preflightTimeoutSeconds <= 0) preflightTimeoutSeconds = 5;
        if (loopDetectionThreshold <= 0) loopDetectionThreshold = 5;
        if (triageMaxAttempts <= 0) triageMaxAttempts = 3;
        if (webhookUrl == null) webhookUrl = "none";
        if (webhookFormat == null) webhookFormat = "generic";
        if (stepTimeoutSeconds <= 0) stepTimeoutSeconds = 30;
        if (maxTurnToolCalls <= 0) maxTurnToolCalls = 50;
        if (maxTurnWallMinutes <= 0) maxTurnWallMinutes = 30;
    }

    public static EngineConfig defaults() {
        return new EngineConfig(
                10, 30, 3,
                true, 60, 5, true, true,
                5,
                false, 3,
                false, "none", "generic",
                30, 50, 0, 30
        );
    }
}



    private final Stores stores;
    private final ExtensionPorts extensionPorts;
    private final Clock clock;
    public de.augmentia.oc.engine.core.port.world.ToolTokenService toolTokenService =
            de.augmentia.oc.engine.core.port.world.ToolTokenService.disabled();
    private final SessionService sessionService;
    private final Coordinator coordinator;
    private final StorageConfig storageConfig;
    private final boolean recoveryEnabled;
    private final de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider;
    private final EngineConfig engineConfig;
    private final de.augmentia.oc.engine.runtime.resilience.ErrorClassifier errorClassifier;
    private final de.augmentia.oc.engine.runtime.resilience.RetryPolicy retryPolicy;
    private final de.augmentia.oc.engine.runtime.resilience.LoopDetector loopDetector;
    private final de.augmentia.oc.engine.runtime.resilience.TriageService triageService;
    private final de.augmentia.oc.engine.runtime.resilience.PreflightChecker preflightChecker;
    private final de.augmentia.oc.engine.runtime.resilience.WebhookAlerter webhookAlerter;
    private final java.util.concurrent.atomic.AtomicLong preflightFailures = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong triageAttempts = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong triageNonRetryable = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong loopDetections = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong verifyFailures = new java.util.concurrent.atomic.AtomicLong();
    private final EventPublisher eventPublisher;
    private final AgentResolver agentResolver;
    private final TracerProvider tracerProvider;
    private final java.util.concurrent.ConcurrentHashMap<String, AgentConfig> agentRegistry =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final de.augmentia.oc.engine.runtime.compaction.CompactionService compactionService;
    private final de.augmentia.oc.engine.runtime.context.ContextBuilder contextBuilder;
    private final de.augmentia.oc.engine.runtime.step.Step step;
    private final de.augmentia.oc.engine.runtime.workflow.WorkflowStepProvider workflowStepProvider;
    private final de.augmentia.oc.engine.core.contract.tool.ToolSnapshot toolSnapshot;
    public de.augmentia.oc.engine.core.contract.tool.ToolSnapshot globalToolSnapshot() {
        return toolSnapshot;
    }

    public ExtensionPorts extensionPorts() {
        return extensionPorts;
    }

    public de.augmentia.oc.engine.core.port.store.ProvenanceStore provenanceStore() {
        return extensionPorts.provenance();
    }

    public de.augmentia.oc.engine.core.port.store.MemoryStore memoryStore() {
        return extensionPorts.memory();
    }

    public de.augmentia.oc.engine.core.port.runtime.DecisionModel decisionModel() {
        return extensionPorts.decision();
    }

    public de.augmentia.oc.engine.core.contract.permission.DataPolicy dataPolicy() {
        return extensionPorts.dataPolicy();
    }

    /**
     * Optional helper: classify the relation of a new prompt to a running
     * session using the configured decision model. Returns
     * {@link de.augmentia.oc.engine.core.model.PromptRelation#UNKNOWN} when no
     * decision model is configured or the call fails (fail-open).
     */
    public de.augmentia.oc.engine.core.model.PromptRelation classifyPromptRelation(
            de.augmentia.oc.engine.core.model.SessionId session, String text) {
        return classifyPromptRelationDetailed(session, text).relation();
    }

    /**
     * Detailed variant of {@link #classifyPromptRelation}: returns the relation
     * plus the model confidence. Fail-open: UNKNOWN with confidence 0.
     */
    public PromptRelationResult classifyPromptRelationDetailed(
            de.augmentia.oc.engine.core.model.SessionId session, String text) {
        de.augmentia.oc.engine.core.port.runtime.DecisionModel dm = decisionModel();
        if (dm == null || !dm.isAvailable()) {
            return PromptRelationResult.unknown();
        }
        try {
            de.augmentia.oc.engine.core.model.DecisionResult r = dm.decide(
                    new de.augmentia.oc.engine.core.model.DecisionRequest(
                            "prompt_relation",
                            "Classify the relation of the new prompt to the running task.",
                            java.util.List.of(),
                            text == null ? "" : text,
                            PROMPT_RELATION_OPTIONS));
            String a = r.answer() == null ? "" : r.answer().trim().toUpperCase();
            try {
                return new PromptRelationResult(
                        de.augmentia.oc.engine.core.model.PromptRelation.valueOf(a), r.confidence());
            } catch (IllegalArgumentException ignored) {
                return PromptRelationResult.unknown();
            }
        } catch (RuntimeException e) {
            return PromptRelationResult.unknown();
        }
    }

    /** Choice options for the prompt-relation decision (TypeSafe/Flide/Ollama). */
    private static final java.util.Map<String, Object> PROMPT_RELATION_OPTIONS = java.util.Map.of(
            "type", "choice",
            "options", java.util.Map.of(
                    "SUPPLEMENT", "The new prompt adds to the running task.",
                    "CORRECTION", "The new prompt corrects the running task.",
                    "INDEPENDENT", "The new prompt is independent and should be queued.",
                    "CANCEL", "The new prompt cancels the running task.",
                    "STATUS", "The new prompt asks for a status update."));

    /** Relation plus model confidence (0 when unknown/unavailable). */
    public record PromptRelationResult(
            de.augmentia.oc.engine.core.model.PromptRelation relation, double confidence) {
        public PromptRelationResult {
            if (relation == null) relation = de.augmentia.oc.engine.core.model.PromptRelation.UNKNOWN;
            if (confidence < 0) confidence = 0;
        }

        public static PromptRelationResult unknown() {
            return new PromptRelationResult(
                    de.augmentia.oc.engine.core.model.PromptRelation.UNKNOWN, 0.0);
        }
    }

    /** Interrupts the active execution of a session, if any (I-03-05). */
    public boolean interrupt(de.augmentia.oc.engine.core.model.SessionId session) {
        return coordinator.interrupt(session);
    }
    private final de.augmentia.oc.engine.core.contract.provider.ModelInfo modelInfo;
    private int workflowMaxSteps = 100;
    private int workflowNodeMaxRepetitions = 5;
    private de.augmentia.oc.engine.runtime.resilience.PreflightChecker.PreflightContext preflightContext;

    /** Loop mode of a single {@link #runPrompt} invocation. */
    private enum LoopMode { AGENT, WORKFLOW }
    /**
     * Hook for interactive turn boundaries: asks the user a yes/no question
     * ("continue working?") and returns the answer. Implemented by the outer
     * engine via the {@code question} tool; never set in framework-neutral
     * mode, where the soft boundary then simply stops the turn.
     */
    private java.util.function.BiFunction<SessionId, String, Boolean> questionAsker;

    /** Registers the interactive continue-question hook. */
    public void setQuestionAsker(java.util.function.BiFunction<SessionId, String, Boolean> asker) {
        this.questionAsker = asker;
    }

    /** Asks the user whether to keep going; defaults to "no" when no hook is wired. */
    private boolean askContinue(SessionId session, int stepNo) {
        if (questionAsker == null) return false;
        try {
            Boolean answer = questionAsker.apply(session,
                    "The step limit (" + stepNo + " steps) has been reached. Should the agent continue? (yes/no)");
            return Boolean.TRUE.equals(answer);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private QocEngine(Stores stores, ExtensionPorts extensionPorts, StorageConfig storageConfig, Clock clock,
                      SessionService sessionService, Coordinator coordinator,
                      boolean recoveryEnabled,
                      de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider,
                      EngineConfig engineConfig,
                      de.augmentia.oc.engine.runtime.resilience.ErrorClassifier errorClassifier,
                      de.augmentia.oc.engine.runtime.resilience.RetryPolicy retryPolicy,
                      de.augmentia.oc.engine.runtime.resilience.LoopDetector loopDetector,
                      de.augmentia.oc.engine.runtime.resilience.TriageService triageService,
                      de.augmentia.oc.engine.runtime.resilience.PreflightChecker preflightChecker,
                      de.augmentia.oc.engine.runtime.resilience.WebhookAlerter webhookAlerter,
                      EventPublisher eventPublisher,
                      AgentResolver agentResolver,
                      TracerProvider tracerProvider,
                      de.augmentia.oc.engine.runtime.compaction.CompactionService compactionService,
                      de.augmentia.oc.engine.runtime.context.ContextBuilder contextBuilder,
                      de.augmentia.oc.engine.runtime.step.Step step,
                      de.augmentia.oc.engine.runtime.workflow.WorkflowStepProvider workflowStepProvider,
                      de.augmentia.oc.engine.core.contract.tool.ToolSnapshot toolSnapshot,
                      de.augmentia.oc.engine.core.contract.provider.ModelInfo modelInfo) {
        this.stores = stores;
        this.extensionPorts = extensionPorts == null ? ExtensionPorts.defaults() : extensionPorts;
        this.storageConfig = storageConfig;
        this.clock = clock;
        this.sessionService = sessionService;
        this.coordinator = coordinator;
        this.recoveryEnabled = recoveryEnabled;
        this.provider = provider;
        this.engineConfig = engineConfig == null ? EngineConfig.defaults() : engineConfig;
        this.errorClassifier = errorClassifier == null ? new de.augmentia.oc.engine.runtime.resilience.DefaultErrorClassifier(this.engineConfig.loopDetectionThreshold()) : errorClassifier;
        this.retryPolicy = retryPolicy == null ? new de.augmentia.oc.engine.runtime.resilience.RetryPolicy.Default() : retryPolicy;
        this.loopDetector = loopDetector == null ? new de.augmentia.oc.engine.runtime.resilience.LoopDetector.Default() : loopDetector;
        this.triageService = triageService == null ? (this.engineConfig.triageEnabled() ? new de.augmentia.oc.engine.runtime.resilience.LlmTriageService(provider, new de.augmentia.oc.engine.core.model.ModelRef(de.augmentia.oc.engine.core.model.ProviderId.create(), de.augmentia.oc.engine.core.model.ModelId.create())) : new de.augmentia.oc.engine.runtime.resilience.NoOpTriageService()) : triageService;
        this.preflightChecker = preflightChecker;
        this.webhookAlerter = webhookAlerter == null ? new de.augmentia.oc.engine.runtime.resilience.WebhookAlerter.Default(this.engineConfig.webhookUrl(), this.engineConfig.webhookFormat()) : webhookAlerter;
        this.eventPublisher = eventPublisher == null ? EventPublisher.noop() : eventPublisher;
        this.agentResolver = agentResolver == null ? AgentResolver.noop() : agentResolver;
        this.tracerProvider = tracerProvider == null ? TracerProvider.noop() : tracerProvider;
        this.compactionService = compactionService;
        this.contextBuilder = contextBuilder;
        this.step = step;
        this.workflowStepProvider = workflowStepProvider;
        this.toolSnapshot = toolSnapshot;
        this.modelInfo = modelInfo;
    }

    /**
     * Creates a QocEngine with the given storage configuration.
     */
    public static QocEngine create(Stores stores, StorageConfig config, Clock clock,
                                  boolean recoveryEnabled) {
        return create(stores, config, clock, recoveryEnabled, null);
    }

    /**
     * Creates a QocEngine with an optional provider adapter for LLM calls.
     */
    public static QocEngine create(Stores stores, StorageConfig config, Clock clock,
                                  boolean recoveryEnabled,
                                  de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider) {
        return create(stores, config, clock, recoveryEnabled, provider,
                EngineConfig.defaults(),
                EventPublisher.noop(), AgentResolver.noop(), TracerProvider.noop());
    }

    public static QocEngine create(Stores stores, StorageConfig config, Clock clock,
                                  boolean recoveryEnabled,
                                  de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider,
                                  EngineConfig engineConfig,
                                  EventPublisher eventPublisher,
                                  AgentResolver agentResolver,
                                  TracerProvider tracerProvider) {
        return create(stores, config, clock, recoveryEnabled, provider, engineConfig,
                eventPublisher, agentResolver, tracerProvider,
                de.augmentia.oc.engine.core.wiring.ToolDependencies.none());
    }

    public static QocEngine create(Stores stores, StorageConfig config, Clock clock,
                                  boolean recoveryEnabled,
                                  de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider,
                                  EngineConfig engineConfig,
                                  EventPublisher eventPublisher,
                                  AgentResolver agentResolver,
                                  TracerProvider tracerProvider,
                                  de.augmentia.oc.engine.core.wiring.ToolDependencies toolDeps,
                                  de.augmentia.oc.engine.core.wiring.ExtensionPorts extensionPorts) {
        if (stores == null) throw new IllegalArgumentException("stores must not be null");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        Clock c = clock;
        StorageConfig cfg = config == null ? new StorageConfig(StorageKind.MEMORY) : config;

        SessionService sessionService = new SessionService(stores.journal(), stores.sessions(),
                session -> { /* no-op SessionWake */ }, stores.sessions()::removeInbox);
        sessionService.setDecisionModel(extensionPorts == null ? null : extensionPorts.decision());

        ExecutionRunner runner = (session, execution, scope) -> new de.augmentia.oc.engine.runtime.execution.ExecutionOutcome.Succeeded();

        Coordinator coordinator = new Coordinator(stores.claims(), stores.journal(), c, runner);

        // CompactionService (optional — requires provider)
        de.augmentia.oc.engine.runtime.compaction.CompactionService compService = null;
        if (provider != null) {
            de.augmentia.oc.engine.runtime.compaction.Compaction compaction =
                    new de.augmentia.oc.engine.runtime.compaction.Compaction(stores.journal(), provider);
            compService = new de.augmentia.oc.engine.runtime.compaction.CompactionService(
                    stores.journal(), stores.conversation(), stores.instructions(),
                    compaction, c, provider);
        }

        EngineConfig cfg2 = engineConfig == null ? EngineConfig.defaults() : engineConfig;

        // Resilience components
        de.augmentia.oc.engine.runtime.resilience.ErrorClassifier errClassifier =
                new de.augmentia.oc.engine.runtime.resilience.DefaultErrorClassifier(cfg2.loopDetectionThreshold());
        de.augmentia.oc.engine.runtime.resilience.RetryPolicy rPolicy =
                new de.augmentia.oc.engine.runtime.resilience.RetryPolicy.Default();
        de.augmentia.oc.engine.runtime.resilience.LoopDetector lDetector =
                new de.augmentia.oc.engine.runtime.resilience.LoopDetector.Default();
        de.augmentia.oc.engine.runtime.resilience.TriageService triageSvc =
                cfg2.triageEnabled()
                        ? new de.augmentia.oc.engine.runtime.resilience.LlmTriageService(provider,
                                new de.augmentia.oc.engine.core.model.ModelRef(
                                        de.augmentia.oc.engine.core.model.ProviderId.create(),
                                        de.augmentia.oc.engine.core.model.ModelId.create()))
                        : new de.augmentia.oc.engine.runtime.resilience.NoOpTriageService();
        de.augmentia.oc.engine.runtime.resilience.PreflightChecker preflightChk =
                cfg2.preflightEnabled()
                        ? new de.augmentia.oc.engine.runtime.resilience.DefaultPreflightChecker(
                                java.net.http.HttpClient.newBuilder()
                                        .connectTimeout(java.time.Duration.ofSeconds(Math.max(1, cfg2.preflightTimeoutSeconds())))
                                        .build(),
                                java.time.Duration.ofSeconds(Math.max(1, cfg2.preflightTimeoutSeconds())),
                                java.time.Duration.ofSeconds(Math.max(0, cfg2.preflightTtlSeconds())),
                                cfg2.preflightDnsEnabled(), cfg2.preflightHttpCheckEnabled())
                        : null;
        de.augmentia.oc.engine.runtime.resilience.WebhookAlerter webhookAlt =
                new de.augmentia.oc.engine.runtime.resilience.WebhookAlerter.Default(cfg2.webhookUrl(), cfg2.webhookFormat());

        // Wrap resilience components with the optional DecisionModel (fail-open).
        de.augmentia.oc.engine.core.port.runtime.DecisionModel dm =
                extensionPorts == null ? null : extensionPorts.decision();
        lDetector = new de.augmentia.oc.engine.runtime.resilience.DecisionLoopDetector(lDetector, dm);
        triageSvc = new de.augmentia.oc.engine.runtime.resilience.DecisionTriageService(triageSvc, dm);

        // ContextBuilder (without security — least-privilege defaults)
        de.augmentia.oc.engine.runtime.skill.SkillCatalog skillCatalog =
                de.augmentia.oc.engine.runtime.skill.SkillCatalog.empty();
        de.augmentia.oc.engine.core.port.world.InstructionLoader instructionLoader =
                de.augmentia.oc.engine.core.port.world.InstructionLoader.none();
        de.augmentia.oc.engine.runtime.context.ContextBuilder cb = new de.augmentia.oc.engine.runtime.context.ContextBuilder(
                stores.conversation(), skillCatalog, instructionLoader,
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.runtime.skill.SkillFilterOptions.defaults(),
                de.augmentia.oc.engine.runtime.context.ContextBuilder.leastPrivilegeDefaults());
        cb.setDecisionModel(dm);

        // ToolRegistry with basic file tools (without security/permission checks)
        java.nio.file.Path workspacePath = (toolDeps != null && toolDeps.workspacePath() != null)
                ? toolDeps.workspacePath()
                : java.nio.file.Path.of(".").toAbsolutePath().normalize();

        de.augmentia.oc.engine.core.contract.permission.PermissionGate noOpGate = request -> {};

        de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry registry = new de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry();

        if (toolDeps != null && toolDeps.fileSystem() != null) {
            de.augmentia.oc.engine.core.port.world.FileSystem fileSystem = toolDeps.fileSystem();
            de.augmentia.oc.engine.runtime.tool.fs.FileAccess fileAccess = new de.augmentia.oc.engine.runtime.tool.fs.FileAccess(fileSystem, workspacePath, workspacePath);
            registry.register(de.augmentia.oc.engine.runtime.tool.fs.ReadTool.definition(fileSystem, fileAccess, noOpGate, stores.toolOutputs()));
            registry.register(de.augmentia.oc.engine.runtime.tool.fs.WriteTool.definition(fileSystem, fileAccess, noOpGate));
            registry.register(de.augmentia.oc.engine.runtime.tool.fs.EditTool.definition(fileSystem, fileAccess, noOpGate));
            registry.register(de.augmentia.oc.engine.runtime.tool.fs.GlobTool.definition(fileSystem, fileAccess, noOpGate));
            registry.register(de.augmentia.oc.engine.runtime.tool.fs.GrepTool.definition(fileSystem, fileAccess, noOpGate, stores.toolOutputs()));
            registry.register(de.augmentia.oc.engine.runtime.tool.fs.PatchTool.definition(fileSystem, fileAccess, noOpGate));
        }

        // Shell + Process tools
        de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry processRegistry = new de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry();
        if (toolDeps != null && toolDeps.processRunner() != null) {
            de.augmentia.oc.engine.core.port.world.ProcessRunner processRunner = toolDeps.processRunner();
            registry.register(de.augmentia.oc.engine.runtime.tool.shell.ShellTool.definition(
                    processRunner, workspacePath, workspacePath, null, noOpGate, processRegistry));
            // Worktree tools (opt-in: engine.worktree.enabled=true; additionally
            // requires git and a git working tree).
            boolean worktreeEnabled = Boolean.parseBoolean(
                    System.getProperty("engine.worktree.enabled",
                            System.getenv().getOrDefault("ENGINE_WORKTREE_ENABLED", "false")));
            if (worktreeEnabled
                    && de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool.available(processRunner, workspacePath)) {
                de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool worktrees =
                        new de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool(processRunner, workspacePath);
                registry.register(de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool.create(worktrees));
                registry.register(de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool.list(worktrees));
                registry.register(de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool.remove(worktrees));
            }
        }
        registry.register(de.augmentia.oc.engine.runtime.tool.shell.ProcessTool.definition(processRegistry));

        // Web tools
        if (toolDeps != null && toolDeps.httpTransport() != null) {
            de.augmentia.oc.engine.core.port.world.HttpTransport httpTransport = toolDeps.httpTransport();
            registry.register(de.augmentia.oc.engine.runtime.tool.web.WebFetchTool.definition(
                    new de.augmentia.oc.engine.runtime.tool.web.WebFetchTool(httpTransport)));
            registry.register(de.augmentia.oc.engine.runtime.tool.web.SecureRestTool.definition(
                    new de.augmentia.oc.engine.runtime.tool.web.SecureRestTool(httpTransport)));
            registry.register(de.augmentia.oc.engine.runtime.tool.web.FirecrawlTool.definition(
                    new de.augmentia.oc.engine.runtime.tool.web.FirecrawlTool(httpTransport, "", "https://mcp.firecrawl.dev/v2/mcp")));
        }

        // Meta tools
        de.augmentia.oc.engine.runtime.tool.meta.TodoTool todoTool = new de.augmentia.oc.engine.runtime.tool.meta.TodoTool();
        registry.register(de.augmentia.oc.engine.runtime.tool.meta.TodoTool.definition(todoTool));

        // Skill tools (empty catalog — no skill files in QOC engine mode)
        de.augmentia.oc.engine.runtime.skill.SkillCatalog emptySkillCatalog =
                de.augmentia.oc.engine.runtime.skill.SkillCatalog.empty();
        java.util.function.Function<de.augmentia.oc.engine.core.model.AgentId, java.util.List<de.augmentia.oc.engine.core.model.Rule>> skillRules =
                agentId -> java.util.List.of();
        registry.register(de.augmentia.oc.engine.runtime.tool.skill.SkillTool.definition(emptySkillCatalog, skillRules));
        registry.register(de.augmentia.oc.engine.runtime.tool.skill.SkillTool.listDefinition(emptySkillCatalog, skillRules));

        // Allow external callers to register additional tools (e.g. MCP tools) before snapshot.
        if (toolDeps != null && toolDeps.toolRegistrar() != null) {
            toolDeps.toolRegistrar().register(registry::register);
        }

        java.util.List<de.augmentia.oc.engine.core.model.Rule> allowAll = java.util.List.of(
                new de.augmentia.oc.engine.core.model.Rule("*", "*", de.augmentia.oc.engine.core.model.Effect.ALLOW));
        de.augmentia.oc.engine.core.contract.tool.ToolSnapshot ts = registry.snapshot(allowAll);

        // Step (with provider, if available)
        de.augmentia.oc.engine.runtime.step.Step st = null;
        if (provider != null) {
            de.augmentia.oc.engine.core.contract.tool.Hooks noopHooks = new de.augmentia.oc.engine.core.contract.tool.Hooks() {
                public de.augmentia.oc.engine.core.contract.tool.ToolCall beforeToolCall(
                        de.augmentia.oc.engine.core.contract.tool.ToolContext context,
                        de.augmentia.oc.engine.core.contract.tool.ToolCall call) { return call; }
                public de.augmentia.oc.engine.core.contract.tool.ToolResult afterToolCall(
                        de.augmentia.oc.engine.core.contract.tool.ToolContext context,
                        de.augmentia.oc.engine.core.contract.tool.ToolResult result) { return result; }
            };
            de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime toolRuntime =
                    new de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime(
                            ts, de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer.defaultAuthorizer(),
                            request -> { /* no-op permission gate */ },
                            noopHooks, stores.toolOutputs(), stores.toolCache(), 30);
            st = new de.augmentia.oc.engine.runtime.step.Step(provider, toolRuntime);
        }

        // ModelInfo (default)
        de.augmentia.oc.engine.core.contract.provider.ModelInfo mi = new de.augmentia.oc.engine.core.contract.provider.ModelInfo(
                de.augmentia.oc.engine.core.model.ModelId.create(),
                de.augmentia.oc.engine.core.model.ProviderId.create(),
                java.util.List.of(),
                new de.augmentia.oc.engine.core.contract.provider.ModelInfo.ModelLimits(128000, 4096),
                new de.augmentia.oc.engine.core.contract.provider.ModelInfo.CostModel(
                        de.augmentia.oc.engine.core.model.Money.zero(),
                        de.augmentia.oc.engine.core.model.Money.zero(),
                        de.augmentia.oc.engine.core.model.Money.zero(),
                        de.augmentia.oc.engine.core.model.Money.zero()),
                new de.augmentia.oc.engine.core.contract.provider.ModelInfo.ModelCapabilities(
                        java.util.List.of(), java.util.List.of()),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none());

        return new QocEngine(stores, extensionPorts, cfg, c, sessionService, coordinator, recoveryEnabled, provider,
                cfg2,
                errClassifier, rPolicy, lDetector, triageSvc, preflightChk, webhookAlt,
                eventPublisher, agentResolver, tracerProvider, compService, cb, st, null, ts, mi);
    }

    /**
     * Convenience overload without explicit extension ports (all no-op).
     */
    public static QocEngine create(Stores stores, StorageConfig config, Clock clock,
                                  boolean recoveryEnabled,
                                  de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider,
                                  EngineConfig engineConfig,
                                  EventPublisher eventPublisher,
                                  AgentResolver agentResolver,
                                  TracerProvider tracerProvider,
                                  de.augmentia.oc.engine.core.wiring.ToolDependencies toolDeps) {
        return create(stores, config, clock, recoveryEnabled, provider, engineConfig,
                eventPublisher, agentResolver, tracerProvider, toolDeps,
                de.augmentia.oc.engine.core.wiring.ExtensionPorts.defaults());
    }

    // --- Public API ---

    /** Configures the step budget for workflow-mode loops. */
    public void setWorkflowMaxSteps(int workflowMaxSteps) {
        this.workflowMaxSteps = workflowMaxSteps;
    }

    public int workflowNodeMaxRepetitions() {
        return workflowNodeMaxRepetitions;
    }

    public void setWorkflowNodeMaxRepetitions(int workflowNodeMaxRepetitions) {
        this.workflowNodeMaxRepetitions = Math.max(1, workflowNodeMaxRepetitions);
    }

    /**
     * Supplies the provider/database context for pre-flight checks. The outer
     * Quarkus engine owns the settings; a standalone engine keeps the neutral
     * fallback context.
     */
    public void setPreflightContext(
            de.augmentia.oc.engine.runtime.resilience.PreflightChecker.PreflightContext preflightContext) {
        this.preflightContext = preflightContext;
    }

    /** True while this JVM is actively executing the session. */
    public boolean isActive(SessionId session) {
        return session != null && coordinator.active(session);
    }

    public Stores stores() { return stores; }
    public StorageConfig storageConfig() { return storageConfig; }
    public Clock clock() { return clock; }
    public de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider() { return provider; }
    public de.augmentia.oc.engine.runtime.compaction.CompactionService compactionService() { return compactionService; }
    public de.augmentia.oc.engine.runtime.context.ContextBuilder contextBuilder() { return contextBuilder; }
    public de.augmentia.oc.engine.runtime.step.Step step() { return step; }
    public de.augmentia.oc.engine.runtime.workflow.WorkflowStepProvider workflowStepProvider() { return workflowStepProvider; }
    public EventPublisher eventPublisher() { return eventPublisher; }
    public AgentResolver agentResolver() { return agentResolver; }
    public TracerProvider tracerProvider() { return tracerProvider; }

    public SessionId createSession(String agentName, String user, String mode) {
        return createSession(agentName, user, mode, null);
    }

    /**
     * Creates a session. A non-null {@code parent} encodes the parent in the
     * child's {@link SessionId} so crash recovery can recognise the child
     * without an extra in-memory registry.
     */
    public SessionId createSession(String agentName, String user, String mode, SessionId parent) {
        String normalizedUser = (user == null || user.isBlank()) ? "anonymous" : user.trim();
        String normalizedMode = (mode == null || mode.isBlank()) ? "workspace" : mode.trim().toLowerCase();
        AgentId agent;
        try {
            agent = AgentId.parse(agentName == null || agentName.isBlank() ? "build" : agentName);
        } catch (IllegalArgumentException e) {
            agent = AgentId.create();
        }
        java.nio.file.Path dir = java.nio.file.Path.of(".").toAbsolutePath().normalize();
        Location location = new Location(dir, ProjectId.create(), Option.none());
        Instant now = clock.now();
        SessionId id = sessionService.create(location, agent, Option.none(), now, parent);
        SessionInfo info = new SessionInfo(id, location.projectId(), location,
                (agentName == null ? "session" : agentName) + " " + now,
                agent, Option.none(), List.of(), de.augmentia.oc.engine.core.model.Money.zero(),
                de.augmentia.oc.engine.core.model.TokenCount.zero(), now, now,
                Option.none(), Option.none(), 0, Option.none(), normalizedUser);
        stores.sessions().put(info);
        return id;
    }

    /**
     * Compacts a session's conversation to a single result message: deletes all
     * messages at or after {@code startSeq} and inserts one assistant message
     * carrying {@code result}. Used by context agents to drop intermediate
     * tool calls from the (shared) workflow session.
     */
    public Seq nextSeq(SessionId session) {
        if (session == null) return null;
        try {
            return stores.conversation().nextSeq(session);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void compactSessionToResult(SessionId session, Seq startSeq, String result) {
        if (session == null || startSeq == null) return;
        de.augmentia.oc.engine.core.model.AgentId agent = null;
        de.augmentia.oc.engine.core.model.ModelRef modelRef = null;
        try {
            de.augmentia.oc.engine.core.model.Option<SessionInfo> info = stores.sessions().info(session);
            if (info.isPresent()) {
                agent = info.orElseThrow().agent();
                if (info.orElseThrow().model().isPresent()) modelRef = info.orElseThrow().model().orElseThrow();
            }
        } catch (RuntimeException ignored) {}
        if (agent == null) agent = de.augmentia.oc.engine.core.model.AgentId.parse("build");
        if (modelRef == null) modelRef = new de.augmentia.oc.engine.core.model.ModelRef(
                de.augmentia.oc.engine.core.model.ProviderId.parse("openai"),
                de.augmentia.oc.engine.core.model.ModelId.create());
        try {
            stores.conversation().deleteFrom(session, startSeq);
        } catch (RuntimeException ignored) {}
        try {
            Seq seq = stores.conversation().nextSeq(session);
            stores.conversation().insert(session, seq,
                    new de.augmentia.oc.engine.core.model.Message.AssistantMessage(
                            de.augmentia.oc.engine.core.model.MessageId.create(),
                            agent,
                            modelRef,
                            java.util.List.of(new de.augmentia.oc.engine.core.model.Content.TextContent(
                                    result == null ? "" : result, clock.now())),
                            de.augmentia.oc.engine.core.model.Option.none(),
                            de.augmentia.oc.engine.core.model.Option.none(),
                            de.augmentia.oc.engine.core.model.Option.none(),
                            de.augmentia.oc.engine.core.model.Option.none(),
                            de.augmentia.oc.engine.core.model.Money.zero(),
                            de.augmentia.oc.engine.core.model.TokenCount.zero(),
                            de.augmentia.oc.engine.core.model.AssistantTime.started(clock.now())));
        } catch (RuntimeException ignored) {}
    }

    public Option<SessionInfo> session(SessionId id) {
        return stores.sessions().info(id);
    }

    public List<SessionInfo> listSessions(String user, boolean isAdmin) {
        return stores.sessions().list().stream()
                .filter(info -> isAdmin || isOwner(info, user))
                .sorted(java.util.Comparator.comparing(SessionInfo::updated).reversed())
                .limit(25)
                .toList();
    }

    public boolean deleteSession(SessionId id) {
        if (id == null) return false;
        stores.sessions().delete(id);
        return true;
    }

    /**
     * Runs a prompt in a new session — full LLM execution (analogous to
     * Quarkus Engine.runPrompt), but without security/permission checks.
     *
     * <p>Flow: create session → store prompt → build context → call Step →
     * return LLM response text.</p>
     */
    public EngineResult runPrompt(String agentName, String prompt) {
        return runPrompt(agentName, prompt, null);
    }

    /** Convenience: returns just the text. */
    public String runPromptText(String agentName, String prompt) {
        return runPrompt(agentName, prompt).text();
    }

    public void recover() {
        if (!recoveryEnabled) return;
        if (storageConfig.defaultKind() == StorageKind.MEMORY) return;
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(QocEngine.class.getName());
        try {
            for (de.augmentia.oc.engine.core.model.Claim claim : stores.claims().open()) {
                de.augmentia.oc.engine.core.model.SessionId sessionId = claim.sessionId();
                if (coordinator.active(sessionId)) continue;

                // Child sessions (subagent or workflow node) are owned by their
                // parent. Resuming them as a generic "build" session would use
                // the wrong agent and duplicate the parent's work.
                if (sessionId.isChild()) {
                    de.augmentia.oc.engine.core.model.SessionId parent =
                            sessionId.parent().orElse(null);
                    try {
                        stores.journal().append(new de.augmentia.oc.engine.core.model.EventDraft(
                                sessionId.value(), 1,
                                new de.augmentia.oc.engine.core.model.SessionEvent.ChildSessionOrphaned(
                                        sessionId, parent,
                                        "child session orphaned; parent must resume")));
                    } catch (RuntimeException ignored) {
                    }
                    stores.claims().release(sessionId);
                    log.fine("child session " + sessionId.value() + " orphaned; parent "
                            + (parent == null ? "unknown" : parent.value()) + " owns recovery");
                    continue;
                }

                if (isWorkflowSession(sessionId)) {
                    // Workflow runs are resumed by the workflow recovery
                    // service, which rebuilds the workflow context from the
                    // journal instead of starting a plain "build" prompt.
                    log.fine("Skipping workflow session during generic recovery: " + sessionId.value());
                    continue;
                }
                if (claim.resumeAttempts() >= de.augmentia.oc.engine.runtime.execution.Recovery.MAX_RESUME_ATTEMPTS) {
                    try {
                        stores.journal().append(new de.augmentia.oc.engine.core.model.EventDraft(
                                sessionId.value(), 1,
                                new de.augmentia.oc.engine.core.model.SessionEvent.ExecutionFailed(
                                        sessionId, new de.augmentia.oc.engine.core.model.SessionError.Unknown(
                                                "resume", "repeatedly interrupted"))));
                    } catch (RuntimeException ignored) {
                    }
                    stores.claims().release(sessionId);
                    continue;
                }
                try {
                    stores.journal().append(new de.augmentia.oc.engine.core.model.EventDraft(
                            sessionId.value(), 1,
                            new de.augmentia.oc.engine.core.model.SessionEvent.SyntheticRaised(
                                    sessionId, de.augmentia.oc.engine.core.eval.PromptLoader.get("server-restarted"),
                                    de.augmentia.oc.engine.core.model.Option.none(),
                                    java.util.Map.of())));
                } catch (RuntimeException ignored) {
                }
                stores.claims().release(sessionId);
                try {
                    de.augmentia.oc.engine.core.model.SessionInfo info =
                            stores.sessions().info(sessionId).orElse(null);
                    String agentName = "build";
                    if (info != null) {
                        String resolved = agentResolver.nameOf(info.agent());
                        if (resolved != null && !resolved.isBlank()) {
                            agentName = resolved;
                        }
                    }
                    this.runPrompt(sessionId, agentName,
                            de.augmentia.oc.engine.core.eval.PromptLoader.get("server-restarted"),
                            agentResolver.config(agentName), false, null, null,
                            de.augmentia.oc.engine.core.model.Principal.anonymous());
                } catch (RuntimeException e) {
                    log.warning("recovery run failed for " + sessionId.value() + ": " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warning("recovery scan failed: " + e.getMessage());
        }
    }
    /**
     * A workflow session owns its recovery: the workflow runner replays
     * {@code WorkflowRun*} events and resumes with the right definition and
     * context. The generic recovery must not turn it into a normal prompt.
     */
    private boolean isWorkflowSession(de.augmentia.oc.engine.core.model.SessionId sessionId) {
        try {
            return !stores.journal()
                    .historyOfType(sessionId.value(), "WorkflowRunStarted",
                            de.augmentia.oc.engine.core.model.Seq.zero())
                    .isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Prepared execution components from the outer engine. Quarkus owns the
     * real tool snapshot, permission gate, skill catalogue and model info; the
     * core engine must use those instead of building a reduced parallel set.
     */
    public record ExecutionOverrides(
            de.augmentia.oc.engine.runtime.context.AgentProfile agentProfile,
            de.augmentia.oc.engine.core.contract.provider.ModelInfo modelInfo,
            de.augmentia.oc.engine.runtime.step.Step step,
            de.augmentia.oc.engine.core.contract.tool.ToolSnapshot toolSnapshot,
            de.augmentia.oc.engine.runtime.context.ContextBuilder contextBuilder,
            de.augmentia.oc.engine.runtime.skill.SkillSelection skillSelection,
            boolean compactionEnabled,
            de.augmentia.oc.engine.core.model.Option<String> promptCacheKey
    ) {
        public ExecutionOverrides {
            if (promptCacheKey == null) promptCacheKey = de.augmentia.oc.engine.core.model.Option.none();
        }

        /** Backwards-compatible: compaction enabled by default, no cache key. */
        public ExecutionOverrides(
                de.augmentia.oc.engine.runtime.context.AgentProfile agentProfile,
                de.augmentia.oc.engine.core.contract.provider.ModelInfo modelInfo,
                de.augmentia.oc.engine.runtime.step.Step step,
                de.augmentia.oc.engine.core.contract.tool.ToolSnapshot toolSnapshot,
                de.augmentia.oc.engine.runtime.context.ContextBuilder contextBuilder,
                de.augmentia.oc.engine.runtime.skill.SkillSelection skillSelection) {
            this(agentProfile, modelInfo, step, toolSnapshot, contextBuilder,
                 skillSelection, true, de.augmentia.oc.engine.core.model.Option.none());
        }

        /** Backwards-compatible: explicit compaction flag, no cache key. */
        public ExecutionOverrides(
                de.augmentia.oc.engine.runtime.context.AgentProfile agentProfile,
                de.augmentia.oc.engine.core.contract.provider.ModelInfo modelInfo,
                de.augmentia.oc.engine.runtime.step.Step step,
                de.augmentia.oc.engine.core.contract.tool.ToolSnapshot toolSnapshot,
                de.augmentia.oc.engine.runtime.context.ContextBuilder contextBuilder,
                de.augmentia.oc.engine.runtime.skill.SkillSelection skillSelection,
                boolean compactionEnabled) {
            this(agentProfile, modelInfo, step, toolSnapshot, contextBuilder,
                 skillSelection, compactionEnabled, de.augmentia.oc.engine.core.model.Option.none());
        }
    }

    /**
     * Agent configuration passed from workflow definitions.
     * Stored in {@link #agentRegistry} by prefixed name ({@code wf:Name:Version}).
     */
    public record AgentConfig(
            String name,
            String model,
            double temperature,
            int maxTokens,
            double topP,
            String systemPrompt,
            java.util.List<String> tools,
            java.util.List<String> guardrailsInput,
            java.util.List<String> guardrailsOutput,
            java.util.List<String> hooks,
            boolean jsonOutput,
            de.augmentia.oc.engine.core.model.Option<de.augmentia.oc.engine.core.json.Schema> responseSchema,
            java.util.List<de.augmentia.oc.engine.core.eval.VerifyRule> verifyOnStop
    ) {
        public AgentConfig {
            if (name == null) name = "wf:default:1.0.0";
            if (model == null) model = "";
            if (tools == null) tools = java.util.List.of();
            if (guardrailsInput == null) guardrailsInput = java.util.List.of();
            if (guardrailsOutput == null) guardrailsOutput = java.util.List.of();
            if (hooks == null) hooks = java.util.List.of();
            if (responseSchema == null) responseSchema = de.augmentia.oc.engine.core.model.Option.none();
            if (verifyOnStop == null) verifyOnStop = java.util.List.of();
        }
        public AgentConfig(String name, String model, double temperature, int maxTokens, double topP,
                           String systemPrompt, java.util.List<String> tools,
                           java.util.List<String> guardrailsInput, java.util.List<String> guardrailsOutput,
                           java.util.List<String> hooks, boolean jsonOutput,
                           de.augmentia.oc.engine.core.model.Option<de.augmentia.oc.engine.core.json.Schema> responseSchema) {
            this(name, model, temperature, maxTokens, topP, systemPrompt, tools, guardrailsInput,
                    guardrailsOutput, hooks, jsonOutput, responseSchema, java.util.List.of());
        }
    }

    /**
     * Looks up or stores an agent config by name.
     * If the name already exists in the registry, the stored config is returned
     * (never overwritten). Otherwise the provided config is stored and returned.
     */
    public AgentConfig resolveAgentConfig(String name, AgentConfig config) {
        AgentConfig existing = agentRegistry.get(name);
        if (existing != null) return existing;
        if (config != null) {
            agentRegistry.put(name, config);
            return config;
        }
        AgentConfig defaultConfig = new AgentConfig(name, null, 0.3, 4096, 1.0, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), false,
                de.augmentia.oc.engine.core.model.Option.none());
        agentRegistry.put(name, defaultConfig);
        return defaultConfig;
    }

    /**
     * Runs a prompt with an explicit agent config.
     * The config is stored in the registry by name (if not already present).
     */
    public de.augmentia.oc.engine.core.model.EngineResult runPrompt(String agentName, String prompt, AgentConfig config) {
        return runPrompt(null, agentName, prompt, config, true, null, null, de.augmentia.oc.engine.core.model.Principal.anonymous());

    }

    /**
     * Runs a prompt, optionally on an existing session.
     * If existingSession is null, a new session is created.
     * Quarkus Engine delegates to this method for the core loop.
     */
    public de.augmentia.oc.engine.core.model.EngineResult runPrompt(
            de.augmentia.oc.engine.core.model.SessionId existingSession,
            String agentName, String prompt, AgentConfig config,
            boolean storePrompt,
            de.augmentia.oc.engine.runtime.workflow.WorkflowStepProvider workflowStepProvider,
            Object workflowContext,
            de.augmentia.oc.engine.core.model.Principal principal) {
        return runPrompt(existingSession, agentName, prompt, config, storePrompt,
                workflowStepProvider, workflowContext, principal, null);
    }

    /**
     * Same as above, but lets the outer engine supply its prepared agent
     * profile, model info, step, tool snapshot and context builder.
     */
    public de.augmentia.oc.engine.core.model.EngineResult runPrompt(
            de.augmentia.oc.engine.core.model.SessionId existingSession,
            String agentName, String prompt, AgentConfig config,
            boolean storePrompt,
            de.augmentia.oc.engine.runtime.workflow.WorkflowStepProvider workflowStepProvider,
            Object workflowContext,
            de.augmentia.oc.engine.core.model.Principal principal,
            ExecutionOverrides overrides) {
        String name = (agentName == null || agentName.isBlank()) ? "build" : agentName;
        if (principal == null) principal = de.augmentia.oc.engine.core.model.Principal.anonymous();
        AgentConfig resolved = resolveAgentConfig(name, config);

        de.augmentia.oc.engine.core.model.Option<de.augmentia.oc.engine.core.json.Schema> responseSchema =
                resolved.responseSchema() == null
                        ? de.augmentia.oc.engine.core.model.Option.none()
                        : resolved.responseSchema();
        de.augmentia.oc.engine.core.model.Option<de.augmentia.oc.engine.core.json.JsonValue> structured =
                de.augmentia.oc.engine.core.model.Option.none();

        String normalizedPrompt = prompt == null ? "" : prompt;
        de.augmentia.oc.engine.core.model.SessionId session;
        if (existingSession != null) {
            session = existingSession;
        } else {
            session = createSession(name, "temporal", "workspace");
        }

        // Claim-based execution guard: at most one active execution per session.
        // A leftover claim after a crash is recovered by QocEngine.recover().
        de.augmentia.oc.engine.core.model.Option<de.augmentia.oc.engine.core.model.Claim> claim =
                stores.claims().acquire(session, clock.now());
        if (claim.isEmpty()) {
            return de.augmentia.oc.engine.core.model.EngineResult.failed(
                    "session is already being executed: " + session.value(), false);
        }
        if ((workflowStepProvider == null) != (workflowContext == null)) {
            stores.claims().release(session);
            return de.augmentia.oc.engine.core.model.EngineResult.failed(
                    "workflowStepProvider and workflowContext must be provided together", false);
        }
        LoopMode loopMode = (workflowStepProvider != null && workflowContext != null)
                ? LoopMode.WORKFLOW : LoopMode.AGENT;
        boolean workflowMode = loopMode == LoopMode.WORKFLOW;
        boolean overridesPreflight = overrides != null && overrides.step() != null;
        // Preflight checks (skip in workflow mode and when the outer engine
        // already ran them with its full provider context)
        if (!workflowMode && !overridesPreflight && engineConfig.preflightEnabled() && preflightChecker != null) {
            var preflightCtx = preflightContext != null ? preflightContext
                    : new de.augmentia.oc.engine.runtime.resilience.PreflightChecker.PreflightContext(
                            null, null, () -> true, java.nio.file.Path.of("."));
            var preflightResult = preflightChecker.check(preflightCtx);
            if (!preflightResult.passed()) {
                preflightFailures.incrementAndGet();
                StringBuilder msg = new StringBuilder("Pre-flight checks failed:\n");
                for (var issue : preflightResult.issues()) {
                    msg.append("  - ").append(issue.type()).append(": ").append(issue.message()).append("\n");
                }
                if (engineConfig.webhookEnabled() && webhookAlerter != null) {
                    webhookAlerter.alert(new de.augmentia.oc.engine.runtime.resilience.WebhookAlerter.Alert(
                            session.value(), "preflight", msg.toString(), null,
                            java.util.List.of(), java.time.Instant.now()));
                }
                stores.claims().release(session);
                return de.augmentia.oc.engine.core.model.EngineResult.failed(msg.toString(), false);
            }
        }

        // Store the prompt in the conversation (skip if already stored by caller)
        if (storePrompt) {
        try {
            Seq seq = stores.conversation().nextSeq(session);
            stores.conversation().insert(session, seq,
                    new de.augmentia.oc.engine.core.model.Message.UserMessage(
                            de.augmentia.oc.engine.core.model.MessageId.create(),
                            normalizedPrompt, java.util.List.of(), java.util.List.of(),
                            java.util.List.of(), java.util.Map.of(), clock.now()));
        } catch (RuntimeException e) {
            // Conversation store may not support insert — continue
        }
        }

        // If no provider/step is wired AND not in workflow mode, return placeholder
        if (!workflowMode && (provider == null || step == null)) {
            stores.claims().release(session);
            return de.augmentia.oc.engine.core.model.EngineResult.completed(
                    "Session " + session.value() + " created. Prompt queued (no provider wired): " + normalizedPrompt,
                    "COMPLETED", 0, 0);
        }
        // Schema-aware step/snapshot (structured output via submit_result tool).
        de.augmentia.oc.engine.runtime.step.Step runStep =
                overrides != null && overrides.step() != null ? overrides.step() : step;
        de.augmentia.oc.engine.core.contract.tool.ToolSnapshot runSnapshot =
                overrides != null && overrides.toolSnapshot() != null
                        ? overrides.toolSnapshot() : toolSnapshot;
        de.augmentia.oc.engine.runtime.context.ContextBuilder activeContextBuilder =
                overrides != null && overrides.contextBuilder() != null
                        ? overrides.contextBuilder() : contextBuilder;
        if ((overrides == null || overrides.step() == null)
                && !workflowMode && responseSchema.isPresent() && provider != null) {
            de.augmentia.oc.engine.core.contract.tool.ToolSnapshot base = runSnapshot == null
                    ? (toolSnapshot == null
                            ? new de.augmentia.oc.engine.core.contract.tool.ToolSnapshot(java.util.Map.of())
                            : toolSnapshot)
                    : runSnapshot;
            de.augmentia.oc.engine.core.contract.tool.ToolSnapshot withSchema =
                    de.augmentia.oc.engine.runtime.tool.structured.SubmitResult.withTool(base, responseSchema.orElseThrow());
            de.augmentia.oc.engine.core.contract.tool.Hooks noopHooks = new de.augmentia.oc.engine.core.contract.tool.Hooks() {
                public de.augmentia.oc.engine.core.contract.tool.ToolCall beforeToolCall(
                        de.augmentia.oc.engine.core.contract.tool.ToolContext context,
                        de.augmentia.oc.engine.core.contract.tool.ToolCall call) { return call; }
                public de.augmentia.oc.engine.core.contract.tool.ToolResult afterToolCall(
                        de.augmentia.oc.engine.core.contract.tool.ToolContext context,
                        de.augmentia.oc.engine.core.contract.tool.ToolResult result) { return result; }
            };
            de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime schemaRuntime =
                    new de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime(
                            withSchema, de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer.defaultAuthorizer(),
                            request -> { /* no-op permission gate: submit_result allowed */ },
                            noopHooks, stores.toolOutputs(), stores.toolCache(),
                            (int) Math.max(1, engineConfig.stepTimeoutSeconds()));
            runStep = new de.augmentia.oc.engine.runtime.step.Step(provider, schemaRuntime);
            runSnapshot = withSchema;
        }

        // Full LLM execution with resolved agent config
        try {
            stores.journal().append(new de.augmentia.oc.engine.core.model.EventDraft(
                    session.value(), 1,
                    new de.augmentia.oc.engine.core.model.SessionEvent.ExecutionStarted(session)));
            SessionInfo info = stores.sessions().info(session).orElseThrow();
            AgentId agentId;
            try {
                agentId = AgentId.parse(name);
            } catch (IllegalArgumentException e) {
                agentId = AgentId.create();
            }
            de.augmentia.oc.engine.runtime.context.AgentProfile agent =
                    overrides != null && overrides.agentProfile() != null
                            ? overrides.agentProfile()
                            : new de.augmentia.oc.engine.runtime.context.AgentProfile(
                                    agentId,
                                    Option.some(buildSystemPromptWithVerify(resolved)),
                                    10, java.util.List.of(), false, null,
                                    resolved.verifyOnStop() == null ? java.util.List.of() : resolved.verifyOnStop());

            de.augmentia.oc.engine.core.contract.provider.ModelInfo model =
                    overrides != null && overrides.modelInfo() != null
                            ? overrides.modelInfo()
                            : buildModelInfo(resolved);

            int stepNo = 1;
            StringBuilder sb = new StringBuilder();
            // Step budget:
            //   workflowMode         → workflowMaxSteps (node loop, not agent loop)
            //   agent.maxSteps() > 0 → that limit, with a status notice and a
            //                          "continue?" question at the boundary
            //   otherwise (0)        → unlimited
            int maxSteps = workflowMode ? workflowMaxSteps
                    : (agent.maxSteps() > 0 ? agent.maxSteps() : 0);
            // Interactive sessions (ask mode INTERACTIVE, non-subagent) get a
            // soft boundary with a status message and a yes/no continue question.
            boolean interactiveTurn = !workflowMode
                    && agent.askMode() == de.augmentia.oc.engine.core.contract.permission.AskMode.INTERACTIVE
                    && !"subagent".equalsIgnoreCase(name);
            int softStepLimit = interactiveTurn ? Math.max(1, maxSteps > 0 ? maxSteps : 100) : 0;
            boolean continueApproved = false;
            int retryAttempts = 0;
            int maxRetries = 2;
            boolean leakRetried = false;
            boolean leakRepairPending = false;
            final String LEAK_REPAIR_HINT =
                    "The previous attempt returned provider-native tool-call markup instead of a final answer. "
                            + "Do not emit DSML or other provider-native tool-call markup; use proper tool calls or plain text.";
            int totalToolCalls = 0;
            long totalTokens = 0;
            boolean workflowDone = false;
            java.time.Instant turnStarted = clock.now();
            java.util.List<de.augmentia.oc.engine.core.contract.tool.ToolCall> recentToolCalls = new java.util.ArrayList<>();
            de.augmentia.oc.engine.runtime.resilience.LoopDetector loopDetector = new de.augmentia.oc.engine.runtime.resilience.LoopDetector.Default();
            de.augmentia.oc.engine.core.eval.TurnPolicy turnPolicy = new de.augmentia.oc.engine.core.eval.TurnPolicy(
                    maxSteps > 0 ? maxSteps + 1 : Integer.MAX_VALUE,
                    Math.max(1, engineConfig.maxTurnToolCalls()), Math.max(0, (int) engineConfig.maxTurnTokens()),
                    java.time.Duration.ofMinutes(Math.max(1, engineConfig.maxTurnWallMinutes())));

            /** Streams a plain status line to the live SSE channel (UI-safe). */
            java.util.function.Consumer<String> statusSink = message -> {
                try {
                    eventPublisher.publishLive(session.value(),
                            de.augmentia.oc.engine.core.json.Json.encode(
                                    de.augmentia.oc.engine.core.event.ContractCodec.encode(
                                            new de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta(
                                                    "status", message),
                                            de.augmentia.oc.engine.core.contract.provider.ModelEvent.class)));
                } catch (RuntimeException ignored) {
                    // best-effort status streaming; do not fail the turn
                }
            };

            java.util.function.Consumer<de.augmentia.oc.engine.core.contract.provider.ModelEvent> liveSink =
                    event -> {
                        try {
                            eventPublisher.publishLive(session.value(),
                                    de.augmentia.oc.engine.core.json.Json.encode(
                                            de.augmentia.oc.engine.core.event.ContractCodec.encode(
                                                    event, de.augmentia.oc.engine.core.contract.provider.ModelEvent.class)));
                        } catch (RuntimeException ignored) {
                            // best-effort live streaming; do not fail the turn
                        }
                    };

            while (maxSteps <= 0 || stepNo <= maxSteps) {
                // Interactive soft boundary: report progress and ask whether to
                // keep going before running further steps.
                if (interactiveTurn && softStepLimit > 0 && stepNo > softStepLimit && !continueApproved) {
                    statusSink.accept("\n\n---\n\n**[Status] Step " + (stepNo - 1) + " von "
                            + softStepLimit + " reached.** Continuation required.");
                    boolean proceed = askContinue(session, stepNo);
                    if (!proceed) break;
                    continueApproved = true; // one approval extends the whole turn
                    softStepLimit += Math.max(softStepLimit, 100);
                }

                de.augmentia.oc.engine.runtime.step.StepResult result;
                if (workflowMode) {
                    java.util.logging.Logger.getLogger(QocEngine.class.getName()).fine("Workflow mode: step=" + stepNo);
                    result = workflowStepProvider.provideStep(stepNo, workflowContext);
                } else {
                    de.augmentia.oc.engine.runtime.skill.SkillSelection selection =
                            overrides == null ? null : overrides.skillSelection();
                    de.augmentia.oc.engine.runtime.context.Prepared prepared = selection != null
                            ? activeContextBuilder.prepare(info, agent, model, runSnapshot,
                                    de.augmentia.oc.engine.core.model.Seq.zero(), stepNo,
                                    de.augmentia.oc.engine.core.model.Option.none(),
                                    de.augmentia.oc.engine.core.model.Option.none(), selection,
                                    overrides == null ? de.augmentia.oc.engine.core.model.Option.none() : overrides.promptCacheKey())
                            : activeContextBuilder.prepare(info, agent, model, runSnapshot,
                                    de.augmentia.oc.engine.core.model.Seq.zero(), stepNo,
                                    de.augmentia.oc.engine.core.model.Option.none(),
                                    de.augmentia.oc.engine.core.model.Option.none(), null,
                                    overrides == null ? de.augmentia.oc.engine.core.model.Option.none() : overrides.promptCacheKey());

                    if (leakRepairPending) {
                        prepared = prepared.withSystem(LEAK_REPAIR_HINT);
                        leakRepairPending = false;
                    }

                    de.augmentia.oc.engine.core.contract.tool.ToolContext toolContext =
                            new de.augmentia.oc.engine.core.contract.tool.ToolContext(
                                    session, info.agent(),
                                    de.augmentia.oc.engine.core.model.MessageId.create(),
                                    de.augmentia.oc.engine.core.model.CallId.create(),
                                    null, principal, toolTokenService);

                    result = runStep.run(prepared, toolContext, liveSink);
                }

                if (!workflowMode
                        && (overrides == null || overrides.compactionEnabled())
                        && result instanceof de.augmentia.oc.engine.runtime.step.StepResult.NeedsCompaction) {
                    // Compact and retry
                    if (compactionService != null) try {
                        compactionService.compact(session, de.augmentia.oc.engine.core.model.CompactionReason.OVERFLOW,
                                new de.augmentia.oc.engine.core.model.ModelRef(model.providerId(), model.id()),
                                de.augmentia.oc.engine.core.model.Option.none(),
                                de.augmentia.oc.engine.core.model.Seq.of(stores.journal().lastSeq(session.value()).value()),
                                clock.now());
                    } catch (RuntimeException e) {
                        // Compaction failed — continue anyway
                    }
                    de.augmentia.oc.engine.runtime.skill.SkillSelection selection =
                            overrides == null ? null : overrides.skillSelection();
                    de.augmentia.oc.engine.runtime.context.Prepared prepared = selection != null
                            ? activeContextBuilder.prepare(info, agent, model, runSnapshot,
                                    de.augmentia.oc.engine.core.model.Seq.zero(), stepNo,
                                    de.augmentia.oc.engine.core.model.Option.none(),
                                    de.augmentia.oc.engine.core.model.Option.none(), selection,
                                    overrides == null ? de.augmentia.oc.engine.core.model.Option.none() : overrides.promptCacheKey())
                            : activeContextBuilder.prepare(info, agent, model, runSnapshot,
                                    de.augmentia.oc.engine.core.model.Seq.zero(), stepNo,
                                    de.augmentia.oc.engine.core.model.Option.none(),
                                    de.augmentia.oc.engine.core.model.Option.none(), null,
                                    overrides == null ? de.augmentia.oc.engine.core.model.Option.none() : overrides.promptCacheKey());
                    de.augmentia.oc.engine.core.contract.tool.ToolContext toolContext =
                            new de.augmentia.oc.engine.core.contract.tool.ToolContext(
                                    session, info.agent(),
                                    de.augmentia.oc.engine.core.model.MessageId.create(),
                                    de.augmentia.oc.engine.core.model.CallId.create(),
                                    null, principal, toolTokenService);
                    result = runStep.run(prepared, toolContext, liveSink);
                }

                if (result instanceof de.augmentia.oc.engine.runtime.step.StepResult.WorkflowDone) {
                    workflowDone = true;
                    break;
                }
                if (workflowMode && result instanceof de.augmentia.oc.engine.runtime.step.StepResult.NeedsCompaction) {
                    stores.claims().release(session);
                    return de.augmentia.oc.engine.core.model.EngineResult.failed(
                            "Workflow step requested compaction; insert a compact node before the context agent",
                            false);
                }

                if (result instanceof de.augmentia.oc.engine.runtime.step.StepResult.Paused paused) {
                    // Suspend session and return paused result
                    return de.augmentia.oc.engine.core.model.EngineResult.paused(paused.approvalId(), paused.approvalUrl(),
                            "Session " + session.value() + " — paused");
                }

                if (result instanceof de.augmentia.oc.engine.runtime.step.StepResult.Failed failed) {
                    boolean isLeak = failed.kind() instanceof de.augmentia.oc.engine.core.contract.provider.FailureKind.InvalidOutput io
                            && io.classification() == de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification.TOOL_CALL_LEAK;
                    if (isLeak) {
                        if (!leakRetried) {
                            leakRetried = true;
                            leakRepairPending = true;
                            java.util.logging.Logger.getLogger(QocEngine.class.getName())
                                    .warning("Session " + session.value() + " step " + stepNo
                                            + ": tool-call markup detected; retrying once with repair hint");
                            statusSink.accept("[Retry] Tool-call markup detected in LLM response; retrying with repair hint");
                            stepNo++;
                            continue;
                        }
                        stores.claims().release(session);
                        return de.augmentia.oc.engine.core.model.EngineResult.failed(
                                "Session " + session.value() + " — LLM failed: " + failed.message(), false);
                    }
                    int maxAttempts = Math.max(1, engineConfig.retryMaxAttempts());
                    if (retryAttempts < maxAttempts) {
                        // Phase 1: Error classification
                        de.augmentia.oc.engine.runtime.resilience.ErrorClassifier.ErrorType errorType =
                                errorClassifier.classify(new IllegalStateException(failed.message()),                                        new de.augmentia.oc.engine.runtime.resilience.ErrorClassifier.ErrorContext(
                                                null, failed.message(), 0));

                        if (errorType == de.augmentia.oc.engine.runtime.resilience.ErrorClassifier.ErrorType.PERMANENT) {
                            if (engineConfig.webhookEnabled() && webhookAlerter != null) {
                                webhookAlerter.alert(new de.augmentia.oc.engine.runtime.resilience.WebhookAlerter.Alert(
                                        session.value(), "permanent", failed.message(), null,
                                        java.util.List.of(), java.time.Instant.now()));
                            }
                            return de.augmentia.oc.engine.core.model.EngineResult.failed(
                                    "Session " + session.value() + " — LLM failed: " + failed.message(), false);
                        }

                        // Phase 2: Triage for correctable errors
                        if (errorType == de.augmentia.oc.engine.runtime.resilience.ErrorClassifier.ErrorType.CORRECTABLE
                                && engineConfig.triageEnabled() && triageService != null) {
                            triageAttempts.incrementAndGet();
                            var triageCtx = new de.augmentia.oc.engine.runtime.resilience.TriageService.TriageContext(
                                    null, failed.message(), null, null, retryAttempts, engineConfig.triageMaxAttempts());
                            var triageResult = triageService.analyze(triageCtx);
                            if (!triageResult.retryable()) {
                                triageNonRetryable.incrementAndGet();
                                return de.augmentia.oc.engine.core.model.EngineResult.failed(
                                        "Session " + session.value() + " — LLM failed: " + failed.message(), false);
                            }
                        }

                        // Delay
                        long delaySeconds;
                        if (retryPolicy != null) {
                            java.time.Duration d = retryPolicy.delay(retryAttempts, errorType);
                            delaySeconds = d.toSeconds();
                        } else if (retryAttempts == 0) {
                            delaySeconds = Math.max(0, engineConfig.retryDelay1Seconds());
                        } else if (retryAttempts == 1) {
                            delaySeconds = Math.max(0, engineConfig.retryDelay2Seconds());
                        } else {
                            delaySeconds = 0;
                        }
                        if (delaySeconds > 0) {
                            try { Thread.sleep(delaySeconds * 1000L); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return de.augmentia.oc.engine.core.model.EngineResult.cancelled();
                            }
                        }
                        retryAttempts++;
                        stepNo++;
                        continue;
                    }
                    return de.augmentia.oc.engine.core.model.EngineResult.failed(
                            "Session " + session.value() + " — LLM failed: " + failed.message(), true);
                }

                if (result instanceof de.augmentia.oc.engine.runtime.step.StepResult.Finished finished) {
                    // Text deltas and tool calls are already streamed to the UI
                    // exactly once via the live sink passed to Step.run; do not
                    // re-publish finished.events() here (that duplicated them).
                    boolean hasToolCalls = finished.toolCalls() != null && !finished.toolCalls().isEmpty();
                    for (de.augmentia.oc.engine.core.contract.provider.ModelEvent event : finished.events()) {
                        if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta d) {
                            sb.append(d.text());
                        } else if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextEnded e) {
                            if (sb.length() == 0) sb.append(e.text());
                        }
                    }

                    // Publish tool results via EventPublisher
                    for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
                        de.augmentia.oc.engine.core.contract.tool.ToolCall call = outcome.call();
                        String resultText;
                        if (outcome.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Ok ok) {
                            StringBuilder tr = new StringBuilder();
                            for (de.augmentia.oc.engine.core.model.ToolContent tc : ok.result().content()) {
                                if (tc instanceof de.augmentia.oc.engine.core.model.ToolContent.TextItem ti) {
                                    if (tr.length() > 0) tr.append('\n');
                                    tr.append(ti.text());
                                }
                            }
                            resultText = tr.toString();
                        } else if (outcome.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Failed failed) {
                            resultText = "Error: " + (failed.error() == null ? "unknown" : failed.error());
                        } else {
                            resultText = "(kein Ergebnis)";
                        }
                        if (resultText.length() > 800) {
                            resultText = resultText.substring(0, 800) + "…";
                        }
                        eventPublisher.publishToolResult(session.value(), call.id().value(), resultText);
                    }

                    // Extract structured result from submit_result tool call (if any).
                    for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome sco : finished.toolCalls()) {
                        if (sco.call().name().value().equals("submit_result")
                                && sco.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Ok ok) {
                            structured = de.augmentia.oc.engine.core.model.Option.some(sco.call().input());
                        }
                    }

                    // Persist assistant turn in conversation
                    java.util.List<de.augmentia.oc.engine.core.model.Content> assistantContent = new java.util.ArrayList<>();
                    String stepText = "";
                    for (de.augmentia.oc.engine.core.contract.provider.ModelEvent event : finished.events()) {
                        if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta d) {
                            stepText += d.text();
                        }
                    }
                    if (!stepText.isBlank()) {
                        assistantContent.add(new de.augmentia.oc.engine.core.model.Content.TextContent(stepText, clock.now()));
                    }
                    for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
                        de.augmentia.oc.engine.core.contract.tool.ToolCall call = outcome.call();
                        de.augmentia.oc.engine.core.model.ToolState state;
                        if (outcome.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Ok ok) {
                            state = new de.augmentia.oc.engine.core.model.ToolState.Completed(call.input(), ok.result().content(), ok.result().metadata());
                        } else if (outcome.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Failed failed) {
                            state = new de.augmentia.oc.engine.core.model.ToolState.Failed(call.input(), failed.error(), de.augmentia.oc.engine.core.model.Option.none(), failed.metadata());
                        } else {
                            state = new de.augmentia.oc.engine.core.model.ToolState.Completed(call.input(), java.util.List.of(), java.util.Map.of());
                        }
                        assistantContent.add(new de.augmentia.oc.engine.core.model.Content.ToolCardContent(
                                new de.augmentia.oc.engine.core.model.ToolCard(call.id(), call.name(), call.input(), state, true, clock.now())));
                    }
                    if (!assistantContent.isEmpty()) {
                        try {
                            de.augmentia.oc.engine.core.model.Seq assistantSeq = stores.conversation().nextSeq(session);
                            stores.conversation().insert(session, assistantSeq, new de.augmentia.oc.engine.core.model.Message.AssistantMessage(
                                    de.augmentia.oc.engine.core.model.MessageId.create(), info.agent(),
                                    info.model().orElse(new de.augmentia.oc.engine.core.model.ModelRef(model.providerId(), model.id())),
                                    assistantContent, de.augmentia.oc.engine.core.model.Option.some(finished.reason()),
                                    de.augmentia.oc.engine.core.model.Option.none(), de.augmentia.oc.engine.core.model.Option.none(),
                                    de.augmentia.oc.engine.core.model.Option.none(), de.augmentia.oc.engine.core.model.Money.zero(),
                                    finished.usage(), de.augmentia.oc.engine.core.model.AssistantTime.started(clock.now())));
                        } catch (RuntimeException e) {
                            // Conversation store may not support insert — continue
                        }
                    }

                    // Structured output is terminal: the reserved tool carries
                    // the final result and must not be treated as a loop.
                    boolean submitted = false;
                    for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
                        if (de.augmentia.oc.engine.runtime.tool.structured.SubmitResult.TOOL_NAME
                                .equals(outcome.call().name().value())) {
                            if (outcome.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Ok ok) {
                                structured = ok.result().output();
                                submitted = true;
                            } else if (outcome.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Failed failed) {
                                return de.augmentia.oc.engine.core.model.EngineResult.failed(
                                        "Session " + session.value() + " — submit_result failed: "
                                                + failed.error(), false);
                            }
                        }
                    }
                    if (submitted) break;

                    // Loop detection
                    for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
                        recentToolCalls.add(outcome.call());
                        if (recentToolCalls.size() > 20) recentToolCalls.remove(0);
                    }
                    if (!finished.toolCalls().isEmpty()) {
                        for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
                            if (loopDetector.isLoop(recentToolCalls, outcome.call(), engineConfig.loopDetectionThreshold())) {
                                loopDetections.incrementAndGet();
                                return de.augmentia.oc.engine.core.model.EngineResult.failed(
                                        "Session " + session.value() + " — Loop detected: " + outcome.call().name().value(), false);
                            }
                        }
                    }

                    // Turn policy. ANSWER_NOW means "force a final answer
                    // now" (TurnPolicy reserves headroom for it); the turn
                    // must not end silently with only tool calls.
                    totalToolCalls += finished.toolCalls().size();
                    totalTokens += finished.usage().total();
                    if (workflowMode) {
                        // Workflow mode never runs TurnPolicy or a forced final LLM step.
                        stepNo++;
                        continue;
                    } else {
                        if (!hasToolCalls) {
                            // Verify-on-Stop Guards: check required conditions before finishing.
                            if (!agent.verifyOnStop().isEmpty()) {
                                List<de.augmentia.oc.engine.core.eval.VerifyRule> failedRules = new java.util.ArrayList<>();
                                for (de.augmentia.oc.engine.core.eval.VerifyRule rule : agent.verifyOnStop()) {
                                    boolean ok = evaluateVerifyCondition(rule.condition(), recentToolCalls);
                                    if (!ok) failedRules.add(rule);
                                }
                                if (!failedRules.isEmpty()) {
                                    verifyFailures.incrementAndGet();
                                    String missing = failedRules.stream()
                                            .map(r -> r.condition().toString())
                                            .collect(java.util.stream.Collectors.joining(", "));
                                    String msg = "\n\n**[Verify-on-Stop] Unvollstaendig:** Erforderliche Schritte nicht ausgefuehrt: "
                                            + missing + ". Bitte fuehre die fehlenden Schritte aus.";
                                    statusSink.accept(msg);
                                    boolean shouldContinue = failedRules.stream()
                                            .anyMatch(r -> r.onFail() == de.augmentia.oc.engine.core.eval.VerifyAction.CONTINUE);
                                    boolean shouldFail = failedRules.stream()
                                            .anyMatch(r -> r.onFail() == de.augmentia.oc.engine.core.eval.VerifyAction.FAIL);
                                    if (shouldFail) {
                                        return de.augmentia.oc.engine.core.model.EngineResult.failed(
                                                "Session " + session.value() + " — Verify-on-Stop failed: " + missing, false);
                                    }
                                    if (shouldContinue) {
                                        stepNo++;
                                        continue;
                                    }
                                    // WARN: fall through to break (mark as incomplete but finish)
                                }
                            }
                            break;
                        }
                    }
                    de.augmentia.oc.engine.core.eval.TurnPolicy.Decision decision =
                            turnPolicy.decide(stepNo, totalToolCalls,
                                    de.augmentia.oc.engine.core.model.TokenCount.of(totalTokens, 0),
                                    java.time.Duration.between(turnStarted, clock.now()));
                    if (decision == de.augmentia.oc.engine.core.eval.TurnPolicy.Decision.CONTINUE) {
                        stepNo++;
                        continue;
                    }
                    // Interactive sessions: the step boundary is soft — report
                    // status and ask whether to keep going instead of ending
                    // the turn abruptly.
                    if (interactiveTurn && softStepLimit > 0 && !continueApproved
                            && stepNo >= softStepLimit
                            && decision == de.augmentia.oc.engine.core.eval.TurnPolicy.Decision.ANSWER_NOW) {
                        statusSink.accept("\n\n---\n\n**[Status] Step " + (stepNo - 1) + " von "
                                + softStepLimit + " reached.** Continuation required.");
                        if (askContinue(session, stepNo)) {
                            continueApproved = true;
                            softStepLimit += Math.max(softStepLimit, 100);
                            continue;
                        }
                    }
                    // ANSWER_NOW or FAIL_TURN: run one forced final step with
                    // tools disabled so the model produces a closing text
                    // answer instead of the turn ending without a reply.
                    de.augmentia.oc.engine.runtime.skill.SkillSelection selection =
                            overrides == null ? null : overrides.skillSelection();
                    de.augmentia.oc.engine.runtime.context.Prepared finalPrepared = selection != null
                            ? activeContextBuilder.prepare(info, agent, model, runSnapshot,
                                    de.augmentia.oc.engine.core.model.Seq.zero(), stepNo,
                                    de.augmentia.oc.engine.core.model.Option.some(
                                            new de.augmentia.oc.engine.core.contract.provider.Choice.None()),
                                    de.augmentia.oc.engine.core.model.Option.none(), selection,
                                    overrides == null ? de.augmentia.oc.engine.core.model.Option.none() : overrides.promptCacheKey())
                            : activeContextBuilder.prepare(info, agent, model, runSnapshot,
                                    de.augmentia.oc.engine.core.model.Seq.zero(), stepNo,
                                    de.augmentia.oc.engine.core.model.Option.some(
                                            new de.augmentia.oc.engine.core.contract.provider.Choice.None()),
                                    de.augmentia.oc.engine.core.model.Option.none(), null,
                                    overrides == null ? de.augmentia.oc.engine.core.model.Option.none() : overrides.promptCacheKey());
                    de.augmentia.oc.engine.core.contract.tool.ToolContext finalToolContext =
                            new de.augmentia.oc.engine.core.contract.tool.ToolContext(
                                    session, info.agent(),
                                    de.augmentia.oc.engine.core.model.MessageId.create(),
                                    de.augmentia.oc.engine.core.model.CallId.create(),
                                    null, principal, toolTokenService);
                    de.augmentia.oc.engine.runtime.step.StepResult finalResult =
                            runStep.run(finalPrepared, finalToolContext, liveSink);
                    if (finalResult instanceof de.augmentia.oc.engine.runtime.step.StepResult.Finished finalFinished) {
                        for (de.augmentia.oc.engine.core.contract.provider.ModelEvent event : finalFinished.events()) {
                            if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta d) {
                                sb.append(d.text());
                            } else if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextEnded e) {
                                if (sb.length() == 0) sb.append(e.text());
                            }
                        }
                        // Persist the forced final assistant turn (text only).
                        java.util.List<de.augmentia.oc.engine.core.model.Content> finalContent =
                                new java.util.ArrayList<>();
                        if (!sb.isEmpty()) {
                            finalContent.add(new de.augmentia.oc.engine.core.model.Content.TextContent(
                                    sb.toString(), clock.now()));
                        }
                        // Blocked calls (tools disabled) are persisted so the
                        // history stays consistent, but they do not count as
                        // a reason to keep looping.
                        for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome outcome : finalFinished.toolCalls()) {
                            de.augmentia.oc.engine.core.contract.tool.ToolCall call = outcome.call();
                            de.augmentia.oc.engine.core.model.ToolState state;
                            if (outcome.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Ok ok) {
                                state = new de.augmentia.oc.engine.core.model.ToolState.Completed(
                                        call.input(), ok.result().content(), ok.result().metadata());
                            } else if (outcome.outcome() instanceof de.augmentia.oc.engine.core.contract.tool.ToolOutcome.Failed failed) {
                                state = new de.augmentia.oc.engine.core.model.ToolState.Failed(
                                        call.input(), failed.error(),
                                        de.augmentia.oc.engine.core.model.Option.none(), failed.metadata());
                            } else {
                                state = new de.augmentia.oc.engine.core.model.ToolState.Completed(
                                        call.input(), java.util.List.of(), java.util.Map.of());
                            }
                            finalContent.add(new de.augmentia.oc.engine.core.model.Content.ToolCardContent(
                                    new de.augmentia.oc.engine.core.model.ToolCard(
                                            call.id(), call.name(), call.input(), state, true, clock.now())));
                        }
                        if (!finalContent.isEmpty()) {
                            try {
                                de.augmentia.oc.engine.core.model.Seq finalSeq =
                                        stores.conversation().nextSeq(session);
                                stores.conversation().insert(session, finalSeq,
                                        new de.augmentia.oc.engine.core.model.Message.AssistantMessage(
                                                de.augmentia.oc.engine.core.model.MessageId.create(),
                                                info.agent(),
                                                info.model().orElse(new de.augmentia.oc.engine.core.model.ModelRef(
                                                        model.providerId(), model.id())),
                                                finalContent,
                                                de.augmentia.oc.engine.core.model.Option.some(finalFinished.reason()),
                                                de.augmentia.oc.engine.core.model.Option.none(),
                                                de.augmentia.oc.engine.core.model.Option.none(),
                                                de.augmentia.oc.engine.core.model.Option.none(),
                                                de.augmentia.oc.engine.core.model.Money.zero(),
                                                finalFinished.usage(),
                                                de.augmentia.oc.engine.core.model.AssistantTime.started(clock.now())));
                            } catch (RuntimeException e) {
                                // Conversation store may not support insert — continue
                            }
                        }
                    }
                    break;
                }
            }

            if (workflowMode && !workflowDone) {
                stores.claims().release(session);
                return de.augmentia.oc.engine.core.model.EngineResult.failed(
                        "Workflow exceeded max steps (" + workflowMaxSteps + ") without WorkflowDone", false);
            }

            String answer = sb.toString();
            if (answer.isBlank()) answer = "(no response)";
            try {
                stores.journal().append(new de.augmentia.oc.engine.core.model.EventDraft(
                        session.value(), 1,
                        new de.augmentia.oc.engine.core.model.SessionEvent.ExecutionSucceeded(session)));
            } catch (RuntimeException ignored) {
            }
            return de.augmentia.oc.engine.core.model.EngineResult.completed(
                    "Session " + session.value() + " — LLM response: " + answer,
                    "COMPLETED", 0, (long) totalTokens, structured);
        } catch (RuntimeException e) {
            try {
                stores.journal().append(new de.augmentia.oc.engine.core.model.EventDraft(
                        session.value(), 1,
                        new de.augmentia.oc.engine.core.model.SessionEvent.ExecutionFailed(
                                session, new de.augmentia.oc.engine.core.model.SessionError.Unknown(
                                        "execution", e.getMessage()))));
            } catch (RuntimeException ignored) {
            }
            stores.claims().release(session);
            return de.augmentia.oc.engine.core.model.EngineResult.failed(
                    "Session " + session.value() + " created. Execution failed: " + e.getMessage()
                            + " (prompt: " + normalizedPrompt + ")", true);
        } finally {
            stores.claims().release(session);
        }
    }

    public de.augmentia.oc.engine.core.contract.provider.ModelInfo resolveModelInfo(AgentConfig config) {
        return buildModelInfo(config);
    }

    private de.augmentia.oc.engine.core.contract.provider.ModelInfo buildModelInfo(AgentConfig config) {
        if (config == null || config.model() == null || config.model().isBlank()) {
            return modelInfo;
        }
        de.augmentia.oc.engine.core.model.ModelId modelId = de.augmentia.oc.engine.core.model.ModelId.create();
        de.augmentia.oc.engine.core.contract.provider.ModelInfo.ModelLimits limits =
                new de.augmentia.oc.engine.core.contract.provider.ModelInfo.ModelLimits(
                        128000, config.maxTokens() > 0 ? config.maxTokens() : 4096);
        de.augmentia.oc.engine.core.contract.provider.ModelInfo.CostModel cost =
                new de.augmentia.oc.engine.core.contract.provider.ModelInfo.CostModel(
                        de.augmentia.oc.engine.core.model.Money.zero(),
                        de.augmentia.oc.engine.core.model.Money.zero(),
                        de.augmentia.oc.engine.core.model.Money.zero(),
                        de.augmentia.oc.engine.core.model.Money.zero());
        return new de.augmentia.oc.engine.core.contract.provider.ModelInfo(
                modelId,
                de.augmentia.oc.engine.core.model.ProviderId.create(),
                java.util.List.of(),
                limits, cost,
                new de.augmentia.oc.engine.core.contract.provider.ModelInfo.ModelCapabilities(
                        java.util.List.of(), java.util.List.of()),
                de.augmentia.oc.engine.core.model.Option.none(),
                de.augmentia.oc.engine.core.model.Option.none());
    }

    private boolean isOwner(SessionInfo info, String user) {
        if (info == null || user == null || user.isBlank()) return false;
        String owner = info.ownerId();
        if (owner == null || owner.isBlank()) return true;
        if ("anonymous".equalsIgnoreCase(owner) || "opencode".equalsIgnoreCase(owner)
                || "local".equalsIgnoreCase(owner)) return true;
        return owner.equalsIgnoreCase(user);
    }

    public java.util.Map<String, Long> resilienceMetrics() {
        java.util.Map<String, Long> values = new java.util.LinkedHashMap<>();
        values.put("preflightFailures", preflightFailures.get());
        values.put("triageAttempts", triageAttempts.get());
        values.put("triageNonRetryable", triageNonRetryable.get());
        values.put("loopDetections", loopDetections.get());
        values.put("verifyFailures", verifyFailures.get());
        return values;
    }

    private static boolean evaluateVerifyCondition(de.augmentia.oc.engine.core.eval.VerifyCondition condition,
            List<de.augmentia.oc.engine.core.contract.tool.ToolCall> calledTools) {
        return switch (condition) {
            case de.augmentia.oc.engine.core.eval.VerifyCondition.ToolCalled tc ->
                    calledTools.stream().anyMatch(c -> c.name().value().equals(tc.toolName()));
            case de.augmentia.oc.engine.core.eval.VerifyCondition.ToolCalledWith tcw ->
                    calledTools.stream().anyMatch(c -> c.name().value().equals(tcw.toolName()));
            case de.augmentia.oc.engine.core.eval.VerifyCondition.AnyOf ao ->
                    ao.conditions().stream().anyMatch(c -> evaluateVerifyCondition(c, calledTools));
            case de.augmentia.oc.engine.core.eval.VerifyCondition.AllOf ao ->
                    ao.conditions().stream().allMatch(c -> evaluateVerifyCondition(c, calledTools));
        };
    }


    private static String buildSystemPromptWithVerify(de.augmentia.oc.engine.runtime.QocEngine.AgentConfig resolved) {
        String base = resolved.systemPrompt() == null ? "" : resolved.systemPrompt();
        List<de.augmentia.oc.engine.core.eval.VerifyRule> rules = resolved.verifyOnStop();
        if (rules == null || rules.isEmpty()) return base;
        StringBuilder sb = new StringBuilder(base);
        if (!base.isBlank()) sb.append("\n\n");
        sb.append("Abschluss-Bedingungen: Bevor du fertig bist, stelle sicher, dass folgende ");
        sb.append("Tools aufgerufen wurden: ");
        List<String> tools = new java.util.ArrayList<>();
        for (de.augmentia.oc.engine.core.eval.VerifyRule r : rules) {
            if (r.condition() instanceof de.augmentia.oc.engine.core.eval.VerifyCondition.ToolCalled tc) {
                tools.add(tc.toolName());
            } else if (r.condition() instanceof de.augmentia.oc.engine.core.eval.VerifyCondition.ToolCalledWith tcw) {
                tools.add(tcw.toolName());
            }
        }
        sb.append(String.join(", ", tools)).append(".");
        return sb.toString();
    }

}