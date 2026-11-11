package de.augmentia.oc.engine.runtime;

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
    private final Clock clock;
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
    private final de.augmentia.oc.engine.core.contract.provider.ModelInfo modelInfo;

    private QocEngine(Stores stores, StorageConfig storageConfig, Clock clock,
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
                                  de.augmentia.oc.engine.core.wiring.ToolDependencies toolDeps) {
        if (stores == null) throw new IllegalArgumentException("stores must not be null");
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        Clock c = clock;
        StorageConfig cfg = config == null ? new StorageConfig(StorageKind.MEMORY) : config;

        SessionService sessionService = new SessionService(stores.journal(), stores.sessions(),
                session -> { /* no-op SessionWake */ }, stores.sessions()::removeInbox);

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
            // Worktree tools (only if git is available)
            if (de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool.available(processRunner, workspacePath)) {
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

        return new QocEngine(stores, cfg, c, sessionService, coordinator, recoveryEnabled, provider,
                cfg2,
                errClassifier, rPolicy, lDetector, triageSvc, preflightChk, webhookAlt,
                eventPublisher, agentResolver, tracerProvider, compService, cb, st, null, ts, mi);
    }

    // --- Public API ---

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
        SessionId id = sessionService.create(location, agent, Option.none(), now);
        SessionInfo info = new SessionInfo(id, location.projectId(), location,
                (agentName == null ? "session" : agentName) + " " + now,
                agent, Option.none(), List.of(), de.augmentia.oc.engine.core.model.Money.zero(),
                de.augmentia.oc.engine.core.model.TokenCount.zero(), now, now,
                Option.none(), Option.none(), 0, Option.none(), normalizedUser);
        stores.sessions().put(info);
        return id;
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
                    this.runPrompt(sessionId, "build",
                            de.augmentia.oc.engine.core.eval.PromptLoader.get("server-restarted"),
                            null, false, null, null);
                } catch (RuntimeException e) {
                    log.warning("recovery run failed for " + sessionId.value() + ": " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warning("recovery scan failed: " + e.getMessage());
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
            boolean jsonOutput
    ) {
        public AgentConfig {
            if (name == null) name = "wf:default:1.0.0";
            if (model == null) model = "";
            if (tools == null) tools = java.util.List.of();
            if (guardrailsInput == null) guardrailsInput = java.util.List.of();
            if (guardrailsOutput == null) guardrailsOutput = java.util.List.of();
            if (hooks == null) hooks = java.util.List.of();
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
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), false);
        agentRegistry.put(name, defaultConfig);
        return defaultConfig;
    }

    /**
     * Runs a prompt with an explicit agent config.
     * The config is stored in the registry by name (if not already present).
     */
    public de.augmentia.oc.engine.core.model.EngineResult runPrompt(String agentName, String prompt, AgentConfig config) {
        return runPrompt(null, agentName, prompt, config, true, null, null);

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
            Object workflowContext) {
        String name = (agentName == null || agentName.isBlank()) ? "build" : agentName;
        AgentConfig resolved = resolveAgentConfig(name, config);

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
        // Preflight checks
        if (engineConfig.preflightEnabled() && preflightChecker != null) {
            var preflightCtx = new de.augmentia.oc.engine.runtime.resilience.PreflightChecker.PreflightContext(
                    null, null, () -> true, java.nio.file.Path.of("."));
            var preflightResult = preflightChecker.check(preflightCtx);
            if (!preflightResult.passed()) {
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
        boolean workflowMode = (workflowStepProvider != null && workflowContext != null);
        if (!workflowMode && (provider == null || step == null)) {
            stores.claims().release(session);
            return de.augmentia.oc.engine.core.model.EngineResult.completed(
                    "Session " + session.value() + " created. Prompt queued (no provider wired): " + normalizedPrompt,
                    "COMPLETED", 0, 0);
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
                    new de.augmentia.oc.engine.runtime.context.AgentProfile(
                            agentId,
                            resolved.systemPrompt() != null ? Option.some(resolved.systemPrompt()) : Option.none(),
                            10, java.util.List.of());

            de.augmentia.oc.engine.core.contract.provider.ModelInfo model = buildModelInfo(resolved);

            int stepNo = 1;
            StringBuilder sb = new StringBuilder();
            int maxSteps = 10; // prevent infinite loop
            int retryAttempts = 0;
            int maxRetries = 2;
            int totalToolCalls = 0;
            long totalTokens = 0;
            java.time.Instant turnStarted = clock.now();
            java.util.List<de.augmentia.oc.engine.core.contract.tool.ToolCall> recentToolCalls = new java.util.ArrayList<>();
            de.augmentia.oc.engine.runtime.resilience.LoopDetector loopDetector = new de.augmentia.oc.engine.runtime.resilience.LoopDetector.Default();
            de.augmentia.oc.engine.core.eval.TurnPolicy turnPolicy = new de.augmentia.oc.engine.core.eval.TurnPolicy(
                    maxSteps + 1,
                    Math.max(1, engineConfig.maxTurnToolCalls()), Math.max(0, (int) engineConfig.maxTurnTokens()),
                    java.time.Duration.ofMinutes(Math.max(1, engineConfig.maxTurnWallMinutes())));

            while (stepNo <= maxSteps) {
                de.augmentia.oc.engine.runtime.step.StepResult result;
                if (workflowMode) {
                    java.util.logging.Logger.getLogger(QocEngine.class.getName()).fine("Workflow mode: step=" + stepNo);
                    result = workflowStepProvider.provideStep(stepNo, workflowContext);
                } else {
                    de.augmentia.oc.engine.runtime.context.Prepared prepared =
                            contextBuilder.prepare(info, agent, model, toolSnapshot,
                                    de.augmentia.oc.engine.core.model.Seq.zero(), stepNo);

                    de.augmentia.oc.engine.core.contract.tool.ToolContext toolContext =
                            new de.augmentia.oc.engine.core.contract.tool.ToolContext(
                                    session, info.agent(),
                                    de.augmentia.oc.engine.core.model.MessageId.create(),
                                    de.augmentia.oc.engine.core.model.CallId.create());

                    result = step.run(prepared, toolContext);
                }

                if (!workflowMode && result instanceof de.augmentia.oc.engine.runtime.step.StepResult.NeedsCompaction) {
                    // Compact and retry
                    if (compactionService != null) try {
                        compactionService.compact(session, de.augmentia.oc.engine.core.model.CompactionReason.OVERFLOW,
                                new de.augmentia.oc.engine.core.model.ModelRef(model.providerId(), model.id()),
                                de.augmentia.oc.engine.core.model.Option.none(),
                                de.augmentia.oc.engine.core.model.Seq.of(stores.journal().history(session.value(), de.augmentia.oc.engine.core.model.Seq.zero()).size()),
                                clock.now());
                    } catch (RuntimeException e) {
                        // Compaction failed — continue anyway
                    }
                    de.augmentia.oc.engine.runtime.context.Prepared prepared =
                            contextBuilder.prepare(info, agent, model, toolSnapshot,
                                    de.augmentia.oc.engine.core.model.Seq.zero(), stepNo);
                    de.augmentia.oc.engine.core.contract.tool.ToolContext toolContext =
                            new de.augmentia.oc.engine.core.contract.tool.ToolContext(
                                    session, info.agent(),
                                    de.augmentia.oc.engine.core.model.MessageId.create(),
                                    de.augmentia.oc.engine.core.model.CallId.create());
                    result = step.run(prepared, toolContext);
                }

                if (result instanceof de.augmentia.oc.engine.runtime.step.StepResult.Paused paused) {
                    // Suspend session and return paused result
                    return de.augmentia.oc.engine.core.model.EngineResult.paused(paused.approvalId(), paused.approvalUrl(),
                            "Session " + session.value() + " — paused");
                }

                if (result instanceof de.augmentia.oc.engine.runtime.step.StepResult.Failed failed) {
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
                            var triageCtx = new de.augmentia.oc.engine.runtime.resilience.TriageService.TriageContext(
                                    null, failed.message(), null, null, retryAttempts, engineConfig.triageMaxAttempts());
                            var triageResult = triageService.analyze(triageCtx);
                            if (!triageResult.retryable()) {
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
                            resultText = "Fehler: " + (failed.error() == null ? "unbekannt" : failed.error());
                        } else {
                            resultText = "(kein Ergebnis)";
                        }
                        if (resultText.length() > 800) {
                            resultText = resultText.substring(0, 800) + "…";
                        }
                        eventPublisher.publishToolResult(session.value(), call.id().value(), resultText);
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

                    // Loop detection
                    for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
                        recentToolCalls.add(outcome.call());
                        if (recentToolCalls.size() > 20) recentToolCalls.remove(0);
                    }
                    if (!finished.toolCalls().isEmpty()) {
                        for (de.augmentia.oc.engine.runtime.step.StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
                            if (loopDetector.isLoop(recentToolCalls, outcome.call(), engineConfig.loopDetectionThreshold())) {
                                return de.augmentia.oc.engine.core.model.EngineResult.failed(
                                        "Session " + session.value() + " — Loop detected: " + outcome.call().name().value(), false);
                            }
                        }
                    }

                    // Turn policy
                    totalToolCalls += finished.toolCalls().size();
                    totalTokens += finished.usage().total();
                    if (!hasToolCalls) {
                        break;
                    }
                    de.augmentia.oc.engine.core.eval.TurnPolicy.Decision decision =
                            turnPolicy.decide(stepNo, totalToolCalls,
                                    de.augmentia.oc.engine.core.model.TokenCount.of(totalTokens, 0),
                                    java.time.Duration.between(turnStarted, clock.now()));
                    if (decision != de.augmentia.oc.engine.core.eval.TurnPolicy.Decision.CONTINUE) {
                        break;
                    }
                }
                stepNo++;
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
                    "COMPLETED", 0, (long) totalTokens);
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
}