package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.runtime.provider.client.HttpProviderAdapter;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.contract.provider.Modality;
import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.event.ContractCodec;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.event.EventCodec;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.permission.Reply;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.runtime.permission.engine.ReplyOutcome;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.port.store.ConversationStore;
import de.augmentia.oc.engine.runtime.tool.fs.FileAccess;
import de.augmentia.oc.engine.adapter.fs.CachedFileSystem;
import de.augmentia.oc.engine.adapter.fs.NioFileSystem;
import de.augmentia.oc.engine.adapter.fs.NioInstructionLoader;
import de.augmentia.oc.engine.adapter.net.JdkHttpTransport;
import de.augmentia.oc.engine.adapter.process.JdkProcessRunner;
import de.augmentia.oc.engine.adapter.process.SandboxedProcessRunner;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.core.shell.SandboxMode;
import de.augmentia.oc.engine.adapter.sql.*;
import de.augmentia.oc.engine.adapter.sqlite.*;
import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.CompactionReason;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.runtime.tool.fs.EditTool;
import de.augmentia.oc.engine.runtime.tool.fs.GlobTool;
import de.augmentia.oc.engine.runtime.tool.fs.GrepTool;
import de.augmentia.oc.engine.runtime.tool.fs.PatchTool;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;
import de.augmentia.oc.engine.runtime.observability.CorrelationContext;
import de.augmentia.oc.engine.runtime.resilience.LoopDetector;
import de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry;
import de.augmentia.oc.engine.runtime.tool.shell.ProcessTool;
import de.augmentia.oc.engine.runtime.tool.shell.ShellTool;
import de.augmentia.oc.engine.runtime.tool.subagent.BackgroundTasks;
import de.augmentia.oc.engine.runtime.tool.subagent.SubagentRunner;
import de.augmentia.oc.engine.runtime.tool.subagent.SubagentTool;
import de.augmentia.oc.engine.runtime.tool.subagent.TaskTool;
import de.augmentia.oc.engine.runtime.tool.web.FirecrawlTool;
import de.augmentia.oc.engine.runtime.tool.web.WebFetchTool;
import de.augmentia.oc.engine.runtime.tool.email.EmailTool;
import de.augmentia.oc.engine.runtime.tool.email.EmailLimits;
import de.augmentia.oc.engine.runtime.tool.fs.WriteTool;
import de.augmentia.oc.engine.runtime.tool.agent.AgentCreateTool;
import de.augmentia.oc.engine.runtime.tool.agent.AgentListTool;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.runtime.permission.engine.PermissionEngine;
import de.augmentia.oc.engine.runtime.tool.mcp.McpInstructions;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.oc.engine.runtime.tool.mcp.McpResourceTools;
import de.augmentia.oc.engine.runtime.tool.meta.QuestionTool;
import de.augmentia.oc.engine.runtime.tool.meta.SessionTools;
import de.augmentia.oc.engine.runtime.tool.meta.TodoTool;
import de.augmentia.oc.engine.runtime.tool.meta.WorktreeTool;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.human.PolicySource;
import de.augmentia.oc.engine.core.port.store.*;
import de.augmentia.oc.engine.core.port.store.SessionStore;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.core.port.world.InstructionLoader;
import de.augmentia.oc.engine.core.port.runtime.SessionWake;
import de.augmentia.oc.engine.runtime.context.AgentProfile;
import de.augmentia.oc.engine.runtime.context.ContextBuilder;
import de.augmentia.oc.engine.runtime.context.ContextSize;
import de.augmentia.oc.engine.runtime.context.Prepared;
import de.augmentia.oc.engine.runtime.context.TitleGenerator;
import de.augmentia.oc.engine.runtime.command.CommandLoader;
import de.augmentia.oc.engine.runtime.compaction.Compaction;
import de.augmentia.oc.engine.runtime.compaction.CompactionService;
import de.augmentia.oc.engine.runtime.session.revert.Revert;
import de.augmentia.oc.engine.runtime.session.revert.RevertResult;
import de.augmentia.oc.engine.runtime.session.core.SessionRetention;
import de.augmentia.oc.engine.runtime.session.core.SessionService;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;
import de.augmentia.oc.engine.runtime.skill.SkillParser;
import de.augmentia.oc.engine.runtime.skill.SkillScanner;
import de.augmentia.oc.engine.runtime.tool.skill.SkillTool;
import de.augmentia.oc.engine.runtime.tool.structured.SubmitResult;
import de.augmentia.oc.engine.runtime.step.Step;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.runtime.step.SubmittedResult;
import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.eval.PermissionCheck;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.skill.SkillAuthorizer;
import de.augmentia.oc.engine.runtime.skill.SkillFilterOptions;
import de.augmentia.oc.engine.runtime.skill.SkillSelection;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Wiring of the engine for the Quarkus outer skin (M14). */
@ApplicationScoped
public class Engine {

    @Inject
    jakarta.enterprise.inject.Instance<DataSource> dataSourceInstance;

    private DataSource dataSource;
    private boolean memoryMode;

    @Inject
    EngineMetrics metrics;

    @Inject
    de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider;

    @Inject
    EventBus eventBus;

    @Inject
    AgentCatalogue agentCatalogue;

    @Inject
    de.augmentia.oc.engine.http.security.PrincipalContext principalContext;

    private static final Logger LOG = Logger.getLogger(Engine.class);

    /** Per-session LLM call counter. */
    private final java.util.concurrent.ConcurrentHashMap<SessionId, java.util.concurrent.atomic.AtomicInteger> llmCallCounts =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Inject
    jakarta.enterprise.inject.Instance<Tracer> tracerInstance;

    @Inject
    EngineConfig config;

    @Inject
    @ConfigProperty(name = "engine.storage", defaultValue = "postgres")
    String storageKind;

    @Inject
    @ConfigProperty(name = "engine.db.kind", defaultValue = "postgresql")
    String dbKind;

    @Inject
    @ConfigProperty(name = "engine.recovery.enabled", defaultValue = "true")
    boolean recoveryEnabled;

    @Inject
    @ConfigProperty(name = "engine.permission.default", defaultValue = "least-privilege")
    String permissionDefault;

    @Inject
    @ConfigProperty(name = "engine.model.context-limit", defaultValue = "512000")
    int modelContextLimit;

    @Inject
    @ConfigProperty(name = "engine.model.output-limit", defaultValue = "128000")
    int modelOutputLimit;

    @Inject
    @ConfigProperty(name = "engine.question.timeout-seconds", defaultValue = "300")
    int questionTimeoutSeconds;

    @Inject
    @ConfigProperty(name = "engine.question.auto-retry", defaultValue = "false")
    boolean questionAutoRetry;

    @Inject
    @ConfigProperty(name = "engine.question.max-retries", defaultValue = "1")
    int questionMaxRetries;

    @Inject
    @ConfigProperty(name = "engine.permission.ask-mode", defaultValue = "deny")
    String permissionAskMode;

    @Inject
    @ConfigProperty(name = "engine.firecrawl.api-key")
    java.util.Optional<String> firecrawlApiKey;

    @Inject
    @ConfigProperty(name = "engine.firecrawl.endpoint", defaultValue = FirecrawlTool.DEFAULT_ENDPOINT)
    String firecrawlEndpoint;

    @Inject
    @ConfigProperty(name = "engine.email.enabled", defaultValue = "true")
    boolean emailEnabled;

    @Inject
    @ConfigProperty(name = "engine.email.read-enabled", defaultValue = "true")
    boolean emailReadEnabled;

    @Inject
    @ConfigProperty(name = "engine.email.attachments-enabled", defaultValue = "false")
    boolean emailAttachmentsEnabled;

    @Inject
    @ConfigProperty(name = "engine.email.allowed-recipients")
    java.util.Optional<String> emailAllowedRecipients;

    @Inject
    @ConfigProperty(name = "engine.email.max-recipients", defaultValue = "10")
    int emailMaxRecipients;

    @Inject
    @ConfigProperty(name = "engine.email.max-attachments", defaultValue = "5")
    int emailMaxAttachments;

    @Inject
    @ConfigProperty(name = "engine.email.max-attachment-bytes", defaultValue = "10485760")
    long emailMaxAttachmentBytes;

    @Inject
    @ConfigProperty(name = "engine.email.max-body-chars", defaultValue = "50000")
    int emailMaxBodyChars;

    @Inject
    @ConfigProperty(name = "engine.email.max-subject-chars", defaultValue = "200")
    int emailMaxSubjectChars;

    @Inject
    @ConfigProperty(name = "engine.email.max-per-hour", defaultValue = "20")
    int emailMaxPerHour;

    @Inject
    @ConfigProperty(name = "engine.email.imap-host", defaultValue = "")
    java.util.Optional<String> emailImapHost;

    @Inject
    @ConfigProperty(name = "engine.email.imap-port", defaultValue = "993")
    int emailImapPort;

    @Inject
    @ConfigProperty(name = "engine.email.imap-ssl", defaultValue = "true")
    boolean emailImapSsl;

    @Inject
    @ConfigProperty(name = "engine.email.imap-starttls", defaultValue = "true")
    boolean emailImapStarttls;

    @Inject
    @ConfigProperty(name = "engine.email.imap-user", defaultValue = "")
    java.util.Optional<String> emailImapUser;

    @Inject
    @ConfigProperty(name = "engine.email.imap-password", defaultValue = "")
    java.util.Optional<String> emailImapPassword;

    @Inject
    @ConfigProperty(name = "engine.email.smtp-host", defaultValue = "")
    java.util.Optional<String> emailSmtpHost;

    @Inject
    @ConfigProperty(name = "engine.email.smtp-port", defaultValue = "587")
    int emailSmtpPort;

    @Inject
    @ConfigProperty(name = "engine.email.smtp-starttls", defaultValue = "true")
    boolean emailSmtpStarttls;

    @Inject
    @ConfigProperty(name = "engine.email.smtp-user", defaultValue = "")
    java.util.Optional<String> emailSmtpUser;

    @Inject
    @ConfigProperty(name = "engine.email.smtp-password", defaultValue = "")
    java.util.Optional<String> emailSmtpPassword;

    @Inject
    @ConfigProperty(name = "engine.session.retention-days", defaultValue = "7")
    long sessionRetentionDays;

    @ConfigProperty(name = "engine.audit.retention-days", defaultValue = "2")
    long auditRetentionDays;

    @Inject
    @ConfigProperty(name = "engine.shell.sandbox.enabled", defaultValue = "true")
    boolean shellSandboxEnabled;

    @Inject
    @ConfigProperty(name = "engine.shell.sandbox.mode", defaultValue = "workspace-write")
    String shellSandboxMode;

    @Inject
    @ConfigProperty(name = "engine.shell.sandbox.uid", defaultValue = "-1")
    int shellSandboxUid;

    @Inject
    @ConfigProperty(name = "engine.shell.sandbox.gid", defaultValue = "-1")
    int shellSandboxGid;

    @Inject
    @ConfigProperty(name = "engine.provider.retry.max-attempts", defaultValue = "3")
    int retryMaxAttempts;

    @Inject
    @ConfigProperty(name = "engine.provider.retry.delay1-seconds", defaultValue = "10")
    long retryDelay1Seconds;

    @Inject
    @ConfigProperty(name = "engine.provider.retry.delay2-seconds", defaultValue = "30")
    long retryDelay2Seconds;

    @Inject
    @ConfigProperty(name = "engine.provider.api-key", defaultValue = "demo-key")
    String providerApiKey;

    @Inject
    @ConfigProperty(name = "engine.preflight.enabled", defaultValue = "true")
    boolean preflightEnabled;

    @Inject
    @ConfigProperty(name = "engine.preflight.ttl-seconds", defaultValue = "60")
    long preflightTtlSeconds;

    @Inject
    @ConfigProperty(name = "engine.preflight.timeout-seconds", defaultValue = "5")
    long preflightTimeoutSeconds;

    @Inject
    @ConfigProperty(name = "engine.preflight.dns.enabled", defaultValue = "true")
    boolean preflightDnsEnabled;

    @Inject
    @ConfigProperty(name = "engine.preflight.http-check.enabled", defaultValue = "true")
    boolean preflightHttpCheckEnabled;

    @Inject
    @ConfigProperty(name = "engine.step.timeout-seconds", defaultValue = "30")
    long stepTimeoutSeconds;

    @Inject
    @ConfigProperty(name = "engine.loop.detection.threshold", defaultValue = "5")
    int loopDetectionThreshold;

    @Inject
    @ConfigProperty(name = "engine.triage.enabled", defaultValue = "false")
    boolean triageEnabled;

    @Inject
    @ConfigProperty(name = "engine.triage.max-attempts", defaultValue = "3")
    int triageMaxAttempts;

    @Inject
    @ConfigProperty(name = "engine.webhook.enabled", defaultValue = "false")
    boolean webhookEnabled;

    @Inject
    @ConfigProperty(name = "engine.webhook.url", defaultValue = "none")
    String webhookUrl;

    @Inject
    @ConfigProperty(name = "engine.webhook.format", defaultValue = "generic")
    String webhookFormat;

    @Inject
    @ConfigProperty(name = "engine.shell.sandbox.bwrap", defaultValue = "bwrap")
    String shellSandboxBwrap;

    @Inject
    @ConfigProperty(name = "engine.shell.sandbox.env-allowlist", defaultValue = "PATH,HOME,LANG,LC_ALL,LC_CTYPE,TMPDIR,TERM")
    String shellSandboxEnvAllowlist;

    @Inject
    @ConfigProperty(name = "engine.shell.sandbox.extraWritable", defaultValue = "")
    java.util.Optional<String> shellSandboxExtraWritable;

    @Inject
    @ConfigProperty(name = "engine.revert.snapshot.enabled", defaultValue = "true")
    boolean revertSnapshotEnabled;

    @Inject
    @ConfigProperty(name = "engine.instructions.enabled", defaultValue = "true")
    boolean instructionsEnabled;

    @Inject
    WebApprovalSource approvalSource;

    @Inject
    DurableApprovalSource durableApprovalSource;

    @Inject
    @ConfigProperty(name = "engine.approval.async-enabled", defaultValue = "false")
    boolean asyncApprovalEnabled;

    @Inject
    DurableQuestionSource questionSource;

    private TodoTool todoTool;

    private Clock clock;
    private Journal journal;
    private ConversationStore conversation;
    private SessionStore sessions;
    private SessionService sessionService;
    private de.augmentia.oc.engine.runtime.QocEngine qocEngine;
    private de.augmentia.oc.engine.core.wiring.Stores storesField;
    private de.augmentia.oc.engine.core.wiring.StorageConfig storageConfigField;
    private ContextBuilder contextBuilder;
    private CommandLoader commandLoader;
    private SkillCatalog skillCatalog = SkillCatalog.empty();
    private TitleGenerator titleGenerator;
    private Step step;

    // Resilience components (Phase 1)
    private de.augmentia.oc.engine.runtime.resilience.PreflightChecker preflightChecker;
    private de.augmentia.oc.engine.runtime.resilience.ErrorClassifier errorClassifier;
    private de.augmentia.oc.engine.runtime.resilience.RetryPolicy retryPolicy;
    private de.augmentia.oc.engine.runtime.resilience.LoopDetector loopDetector;
    private de.augmentia.oc.engine.runtime.resilience.TriageService triageService;
    private de.augmentia.oc.engine.runtime.resilience.WebhookAlerter webhookAlerter;
    private ToolSnapshot toolSnapshot;
    private ToolRegistry registry;
    private InstructionLoader instructionLoader = InstructionLoader.none();
    private PermissionGate permissionGate;
    private ToolOutputStore toolOutput;
    private ToolRuntime toolRuntime;
    private de.augmentia.oc.engine.core.port.store.SessionWorkspaceStore sessionWorkspaces;
    private de.augmentia.oc.engine.core.port.store.ToolResultCache toolResultCache;
    private String instanceId;
    private final java.util.Map<SessionId, String> pausedPrompts = new java.util.concurrent.ConcurrentHashMap<>();
    @Inject
    private Hooks toolHooks = noopHooks();
    @Inject
    ToolAuthorizer toolAuthorizer;
    private ToolAuthorizer effectiveAuthorizer;
    @Inject
    A2AConfig a2aConfig;
    private ModelRef modelRef;
    private FileSystem fileSystem;
    private Path workspacePath;
    private Path workspaceBase;

    private CredentialStore credentialStore;
    private InstructionStore instructions;
    private CompactionService compaction;
    private McpRegistry mcp;
    private List<Rule> allowAll;
    private SubagentTool subagentTool;
    private ProcessRegistry processRegistry;
    private PermissionEngine permissionEngine;
    private SessionRetention sessionRetention;
    private RetentionService retentionService;
    private SnapshotStore snapshots;
    private SnapshotStore storageSnapshots;
    private Revert revert;
    private final java.util.concurrent.ConcurrentHashMap<SessionId, CapturedSnapshot> revertSnapshots =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Roles captured at session creation for subagent authorization. */
    private final java.util.concurrent.ConcurrentHashMap<SessionId, List<String>> sessionRoles =
            new java.util.concurrent.ConcurrentHashMap<>();
    private record CapturedSnapshot(SnapshotId id, List<Path> paths) {}
    private final EventCodec codec = EventCodec.standard();
    private final java.util.concurrent.ConcurrentHashMap<SessionId, Thread> running =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Runtime-owned registry of parent/child subagent sessions. */
    private final de.augmentia.oc.engine.runtime.tool.subagent.SubagentSessionRegistry subagentRegistry =
            new de.augmentia.oc.engine.runtime.tool.subagent.InMemorySubagentSessionRegistry();
    /** Bounded prompt admission: overload degrades to BUSY instead of OOM. */
    private java.util.concurrent.Semaphore promptSlots;

    @ConfigProperty(name = "engine.max.concurrent.prompts", defaultValue = "16")
    int maxConcurrentPrompts;

    @ConfigProperty(name = "engine.turn.max.tool-calls", defaultValue = "64")
    int maxTurnToolCalls;

    /** Cumulative turn token budget; 0 disables the limit. */
    @ConfigProperty(name = "engine.turn.max.tokens", defaultValue = "0")
    long maxTurnTokens;

    @ConfigProperty(name = "engine.turn.max.wall-minutes", defaultValue = "30")
    long maxTurnWallMinutes;

    @ConfigProperty(name = "engine.memory.max-bytes", defaultValue = "1073741824")
    long memoryMaxBytes;

    @PostConstruct
    void wire() {
        effectiveAuthorizer = new SkillAuthorizer(toolAuthorizer == null ? ToolAuthorizer.defaultAuthorizer() : toolAuthorizer);
        clock = new SystemClock();
        promptSlots = new java.util.concurrent.Semaphore(Math.max(1, maxConcurrentPrompts));

        // Startup guard: memory mode disables DB and recovery.
        memoryMode = "memory".equalsIgnoreCase(storageKind);
        if (memoryMode && recoveryEnabled) {
            throw new IllegalStateException("Recovery is not available in memory mode");
        }
        if (memoryMode) {
            // Do not resolve the datasource at all: Quarkus keeps an inactive
            // synthetic bean whose proxy would only fail on first use.
            dataSource = null;
            LOG.info("Storage mode: memory (no database, no recovery)");
        } else {
            dataSource = dataSourceInstance.get();
            if (!de.augmentia.oc.engine.adapter.sql.DbHealthSql.isHealthy(dataSource, 3)) {
                throw new IllegalStateException(
                        "Storage mode " + storageKind + " requires an active datasource");
            }
            LOG.info("Storage mode: " + dbKind);
        }
        de.augmentia.oc.engine.core.wiring.StorageKind kind;
        if (memoryMode) {
            kind = de.augmentia.oc.engine.core.wiring.StorageKind.MEMORY;
        } else if ("sqlite".equalsIgnoreCase(storageKind) || "sqlite".equalsIgnoreCase(dbKind)) {
            kind = de.augmentia.oc.engine.core.wiring.StorageKind.SQLITE;
        } else {
            kind = de.augmentia.oc.engine.core.wiring.StorageKind.POSTGRES;
        }
        if (kind == de.augmentia.oc.engine.core.wiring.StorageKind.SQLITE) Sqlite.enableWal(dataSource);

        // Shared RuntimeState for in-memory adapters (null for DB backends)
        de.augmentia.oc.engine.adapter.memory.RuntimeState memoryState = null;
        if (kind == de.augmentia.oc.engine.core.wiring.StorageKind.MEMORY) {
            memoryState = new de.augmentia.oc.engine.adapter.memory.RuntimeState(
                    new de.augmentia.oc.engine.adapter.memory.MemoryBudget(memoryMaxBytes));
        }

        de.augmentia.oc.engine.core.wiring.StorageConfig storageConfig =
                new de.augmentia.oc.engine.core.wiring.StorageConfig(kind);
        de.augmentia.oc.engine.core.wiring.Stores stores =
                de.augmentia.oc.engine.adapter.StorageFactory.create(
                        storageConfig, memoryState, dataSource, clock, null);

        journal = stores.journal();
        storesField = stores;
        storageConfigField = storageConfig;
        conversation = stores.conversation();
        sessions = (SessionStore) stores.sessions();
        sessionWorkspaces = sessions instanceof de.augmentia.oc.engine.core.port.store.SessionWorkspaceStore
                ? (de.augmentia.oc.engine.core.port.store.SessionWorkspaceStore) sessions : null;
        storageSnapshots = stores.snapshots();
        toolResultCache = stores.toolCache();
        instanceId = java.util.UUID.randomUUID().toString();
        ClaimStore claims = stores.claims();
        ApprovalStore approvals = stores.approvals();
        instructions = stores.instructions();
        toolOutput = stores.toolOutputs();
        credentialStore = stores.credentials();
        // SessionWake is intentionally a no-op: background subagent results are
        // retrieved explicitly via subagent(sessionID=...) or the task registry.
        // Auto-resume via a synthetic prompt is not implemented (H12).
        SessionWake wake = session -> LOG.debugf("SessionWake: no-op for %s", session.value());

        sessionService = new SessionService(journal, sessions, wake, sessions::removeInbox);
        sessionRetention = new SessionRetention(sessions, journal, clock, sessionRetentionDays);
        ApprovalStore permissionApprovals = stores.approvals();
        de.augmentia.oc.engine.core.port.human.ApprovalSource effectiveApproval =
                asyncApprovalEnabled && durableApprovalSource != null
                        ? durableApprovalSource : approvalSource;
        de.augmentia.oc.engine.core.contract.permission.AskMode askMode =
                parseAskMode(permissionAskMode);
        permissionEngine = new PermissionEngine(Option.some(permissionApprovals),
                Option.some(effectiveApproval), Option.some((PolicySource) () -> List.of()), Option.none(), askMode);
        if (approvalSource != null) {
            int orphaned = approvalSource.recoverOrphans();
            if (orphaned > 0) {
                LOG.warnf("recovered %d orphaned approval request(s)", orphaned);
            }
        }

        workspacePath = Path.of(config.workspace()).toAbsolutePath().normalize();
        try {
            workspacePath = workspacePath.toRealPath();
        } catch (java.io.IOException ignored) {
            // Keep the logical path when the workspace does not exist yet.
        }
        workspaceBase = workspacePath;
        buildWorkspaceRuntime();
    }


    private void buildWorkspaceRuntime() {
commandLoader = new CommandLoader(workspacePath,
                Path.of(System.getProperty("user.home", "."), ".config", "opencode"));
        NioFileSystem nio = new NioFileSystem(workspacePath);
        fileSystem = new CachedFileSystem(nio);
        FileSystem fs = fileSystem;
        snapshots = memoryMode
                ? storageSnapshots
                : ("sqlite".equalsIgnoreCase(dbKind)
                        ? new SqliteSnapshots(dataSource, fs)
                        : new SqlSnapshots(dataSource, fs));
        revert = new Revert(journal, Option.some(snapshots), Option.some(instructions));
        // workspaceBase stays at the root workspace set in wire(); it must not
        // follow workspace switches, otherwise allowedBaseFor nests per user.
        FileAccess access = new FileAccess(fs, workspacePath, workspacePath);
        permissionGate = request -> {
            SessionInfo info = sessions.info(request.sessionId()).orElse(null);
            List<Rule> sessionRules = info == null ? List.of() : info.permissions();
            Option<AgentId> agent = info == null ? Option.none() : Option.some(info.agent());
            List<Rule> agentRules = List.of(new Rule("*", "*", Effect.ALLOW), new Rule("grep", "*", Effect.ASK));
            if (info != null) {
                try {
                    String agentName = agentCatalogue.nameOf(info.agent());
                    EngineConfig.AgentConfig cfg = agentCatalogue.config(agentName);
                    // Same rule set as the context builder: global defaults first,
                    // then agent-specific rules (last match wins). Without this,
                    // ENGINE_PERMISSION_DEFAULT is dead as soon as an agent declares
                    // any permission, and unlisted actions (e.g. read) fall through
                    // to NO_MATCH_EFFECT=ASK.
                    agentRules = agentProfile(info.agent(), agentName, cfg).permissions();
                } catch (RuntimeException e) {
                    LOG.warnf("cannot resolve agent rules for %s: %s", info.agent(), e.getMessage());
                }
            }
            LOG.debugf("permission check session=%s action=%s", request.sessionId().value(), request.action());
            Span pspan = tracer().spanBuilder("permission.assert")
                    .setAttribute("session.id", request.sessionId().value())
                    .setAttribute("action", request.action())
                    .startSpan();
            try (Scope pscope = pspan.makeCurrent()) {
            PermissionCheck check = new PermissionCheck(request, agent, agentRules, sessionRules, List.of(), List.of());
            if (asyncApprovalEnabled) {
                permissionEngine.assertAllowedAsync(check);
            } else {
                permissionEngine.assertAllowed(check);
            }
            pspan.setAttribute("result", "checked");
            pspan.end();
            }
        };
        registry = new ToolRegistry();
        registry.register(ReadTool.definition(fs, access, permissionGate, toolOutput));
        registry.register(WriteTool.definition(fs, access, permissionGate));
        registry.register(EditTool.definition(fs, access, permissionGate));
        registry.register(AgentCreateTool.definition(agentCatalogue, permissionGate));
        registry.register(AgentListTool.definition(agentCatalogue));
        registry.register(PatchTool.definition(fs, access, permissionGate));
        registry.register(GlobTool.definition(fs, access, permissionGate));
        registry.register(GrepTool.definition(fs, access, permissionGate, toolOutput));
        ProcessRunner processRunner = createProcessRunner(workspacePath);
        processRegistry = new ProcessRegistry();
        registry.register(ShellTool.definition(processRunner, workspacePath, workspacePath, null,
                permissionGate, processRegistry));
        registry.register(ProcessTool.definition(processRegistry));
        subagentTool = new SubagentTool(null, permissionGate);
        registry.register(SubagentTool.definition(subagentTool));
        registry.register(TaskTool.definition(subagentTool));
        String firecrawlKey = firecrawlApiKey == null ? "" : firecrawlApiKey.orElse("");
        registry.register(FirecrawlTool.definition(
                new FirecrawlTool(new JdkHttpTransport(), firecrawlKey, firecrawlEndpoint)));
        registry.register(WebFetchTool.definition(new WebFetchTool(new JdkHttpTransport())));

        // Email tools (IMAP/SMTP) – only when configured and enabled.
        String imapHost = emailImapHost == null ? "" : emailImapHost.orElse("");
        String smtpHost = emailSmtpHost == null ? "" : emailSmtpHost.orElse("");
        if (emailEnabled && (!imapHost.isBlank() || !smtpHost.isBlank())) {
            JdkEmailClient emailClient = new JdkEmailClient(
                    imapHost.isBlank() ? smtpHost : imapHost,
                    emailImapPort, emailImapSsl, emailImapStarttls,
                    emailImapUser == null ? "" : emailImapUser.orElse(""),
                    emailImapPassword == null ? "" : emailImapPassword.orElse(""),
                    smtpHost.isBlank() ? imapHost : smtpHost,
                    emailSmtpPort, emailSmtpStarttls,
                    emailSmtpUser == null ? "" : emailSmtpUser.orElse(""),
                    emailSmtpPassword == null ? "" : emailSmtpPassword.orElse(""));
            EmailLimits emailLimits = new EmailLimits(
                    emailMaxRecipients, emailMaxAttachments, emailMaxAttachmentBytes,
                    emailMaxBodyChars, emailMaxSubjectChars,
                    splitCsv(emailAllowedRecipients == null ? null : emailAllowedRecipients.orElse("")),
                    List.of(), emailMaxPerHour, emailAttachmentsEnabled);
            if (emailReadEnabled && !imapHost.isBlank()) {
                registry.register(EmailTool.fetchDefinition(emailClient));
                registry.register(EmailTool.readDefinition(emailClient, emailLimits));
            }
            if (!smtpHost.isBlank()) {
                registry.register(EmailTool.sendDefinition(emailClient, emailLimits, workspacePath));
            }
        }
        todoTool = new TodoTool();
        registry.register(TodoTool.definition(todoTool));
        SessionTools.SessionControl sessionControl = new SessionTools.SessionControl() {
            @Override
            public String rename(SessionId session, String title) {
                return renameSession(session, title);
            }

            @Override
            public String move(SessionId session, String directory) {
                return moveSession(session, directory);
            }

            @Override
            public List<SessionTools.ModelSummary> models() {
                return modelSummaries();
            }
        };
        registry.register(SessionTools.rename(sessionControl));
        registry.register(SessionTools.move(sessionControl));
        registry.register(SessionTools.models(sessionControl));
        if (WorktreeTool.available(processRunner, workspacePath)) {
            WorktreeTool worktrees = new WorktreeTool(processRunner, workspacePath);
            registry.register(WorktreeTool.create(worktrees));
            registry.register(WorktreeTool.list(worktrees));
            registry.register(WorktreeTool.remove(worktrees));
        } else {
            LOG.info("worktree tools disabled: git is not available or the workspace is not a git repository");
        }
        int qMaxRetries = questionAutoRetry ? questionMaxRetries : 0;
        long qTimeout = questionTimeoutSeconds;
        registry.register(QuestionTool.definition(new QuestionTool(questionSource, qMaxRetries, qTimeout)));
        // Skills are project text, loaded on request: <workspace>/skill(s),
        // <workspace>/.opencode/skills and any configured skills.paths.
        List<Path> skillRoots = new ArrayList<>();
        skillRoots.add(workspacePath.resolve("skill"));
        skillRoots.add(workspacePath.resolve("skills"));
        skillRoots.add(workspacePath.resolve(".opencode").resolve("skills"));
        for (String configured : config.skillPaths()) {
            Path path = Path.of(configured);
            skillRoots.add(path.isAbsolute() ? path : workspacePath.resolve(path));
        }
        SkillCatalog skillCatalog = config.skillsEnabled()
                ? SkillCatalog.of(new SkillScanner(fs).loadRoots(skillRoots, new SkillParser()),
                        config.skillAllow(), config.skillDeny(), config.skillMax())
                : SkillCatalog.empty();
        this.skillCatalog = skillCatalog;
        if (!skillCatalog.all().isEmpty()) {
            LOG.infof("loaded %d skill(s): %s", skillCatalog.all().size(),
                    skillCatalog.all().stream().map(skill -> skill.id().value()).toList());
        }
        java.util.function.Function<AgentId, List<Rule>> skillRules = agentId -> {
            String agentName = agentCatalogue.nameOf(agentId);
            EngineConfig.AgentConfig cfg = agentName == null ? null : agentCatalogue.config(agentName);
            return agentProfile(agentId, agentName, cfg).permissions();
        };
        registry.register(SkillTool.definition(skillCatalog, skillRules));
        registry.register(SkillTool.listDefinition(skillCatalog, skillRules));
        allowAll = List.of(new Rule("*", "*", Effect.ALLOW));
        mcp = new McpRegistry(registry, new JdkHttpTransport(), new JdkProcessRunner(),
                Option.some(credentialStore));
        mcp.setup(registry, config, a2aConfig);
        registry.register(McpResourceTools.list(mcp.manager()));
        registry.register(McpResourceTools.read(mcp.manager()));
        toolSnapshot = registry.snapshot(allowAll);
        toolRuntime = new ToolRuntime(toolSnapshot, effectiveAuthorizer,
                permissionGate, toolHooks, toolOutput, toolResultCache, stepTimeoutSeconds);
        instructionLoader = instructionsEnabled
                ? new NioInstructionLoader(Path.of(System.getProperty("user.home", "."),
                        ".config", "opencode"))
                : InstructionLoader.none();
        contextBuilder = new ContextBuilder(conversation, skillCatalog, instructionLoader,
                instructions == null ? Option.none() : Option.some(instructions), config.skillFilter(),
                ContextBuilder.leastPrivilegeDefaults());

        // Create QocEngine (BaseEngine) for workflow delegation
        // Use Quarkus's stores for QocEngine (shared data)
        de.augmentia.oc.engine.runtime.QocEngine.EngineConfig qocConfig =
                new de.augmentia.oc.engine.runtime.QocEngine.EngineConfig(
                        retryDelay1Seconds, retryDelay2Seconds, retryMaxAttempts,
                        preflightEnabled, preflightTtlSeconds, preflightTimeoutSeconds,
                        preflightDnsEnabled, preflightHttpCheckEnabled,
                        loopDetectionThreshold, triageEnabled, triageMaxAttempts,
                        webhookEnabled, webhookUrl, webhookFormat,
                        stepTimeoutSeconds, Math.max(1, maxTurnToolCalls), Math.max(0, maxTurnTokens),
                        Math.max(1, maxTurnWallMinutes));
        qocEngine = de.augmentia.oc.engine.runtime.QocEngine.create(
                storesField, storageConfigField, clock, recoveryEnabled,
                provider,
                qocConfig,
                new QuarkusEventPublisher(eventBus),
                new de.augmentia.oc.engine.runtime.callback.AgentResolver() {
                    public String nameOf(de.augmentia.oc.engine.core.model.AgentId agent) {
                        return agentCatalogue.nameOf(agent);
                    }
                    public de.augmentia.oc.engine.runtime.QocEngine.AgentConfig config(String name) {
                        EngineConfig.AgentConfig cfg = agentCatalogue.config(name);
                        if (cfg == null) return null;
                        return new de.augmentia.oc.engine.runtime.QocEngine.AgentConfig(
                                name, cfg.model(), 0.3, 4096, 1.0, null,
                                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), false);
                    }
                },
                new de.augmentia.oc.engine.runtime.callback.TracerProvider() {
                    public Object startSpan(String name, java.util.Map<String, String> attributes) {
                        return tracer().spanBuilder(name).startSpan();
                    }
                    public void endSpan(Object span) {
                        if (span instanceof io.opentelemetry.api.trace.Span s) s.end();
                    }
                },
                de.augmentia.oc.engine.adapter.ToolFactory.create(java.nio.file.Path.of(".")));
        // Crash recovery: resume sessions that were interrupted by a previous process stop.
        if (!memoryMode && recoveryEnabled) {
            try {
                qocEngine.recover();
                LOG.info("Crash recovery completed");
            } catch (RuntimeException e) {
                LOG.warn("Crash recovery failed: " + e.getMessage(), e);
            }
        }
        // Resilience components
        preflightChecker = new de.augmentia.oc.engine.runtime.resilience.DefaultPreflightChecker(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(Math.max(1, preflightTimeoutSeconds)))
                        .build(),
                java.time.Duration.ofSeconds(Math.max(1, preflightTimeoutSeconds)),
                java.time.Duration.ofSeconds(Math.max(0, preflightTtlSeconds)),
                preflightDnsEnabled,
                preflightHttpCheckEnabled);
        errorClassifier = new de.augmentia.oc.engine.runtime.resilience.DefaultErrorClassifier(loopDetectionThreshold);
        retryPolicy = new de.augmentia.oc.engine.runtime.resilience.RetryPolicy.Default();
        loopDetector = new de.augmentia.oc.engine.runtime.resilience.LoopDetector.Default();
        webhookAlerter = new de.augmentia.oc.engine.runtime.resilience.WebhookAlerter.Default(webhookUrl, webhookFormat);
        modelRef = new ModelRef(ProviderId.create(), ModelId.create());
        triageService = triageEnabled
                ? new de.augmentia.oc.engine.runtime.resilience.LlmTriageService(provider, modelRef)
                : new de.augmentia.oc.engine.runtime.resilience.NoOpTriageService();
        titleGenerator = new TitleGenerator(provider, modelRef);
        step = new Step(provider, toolRuntime);
        if (subagentTool != null) {
            subagentTool.setRunner((parent, agentName, prompt, depth) ->
                    runSubagent(parent, agentName, prompt, depth));
        }
        compaction = new CompactionService(journal, conversation, instructions,
                new Compaction(journal, provider), clock, 8, provider);
        retentionService = new RetentionService(sessionRetention, sessions, journal, conversation, clock,
                (int) sessionRetentionDays, (int) auditRetentionDays, sessionWorkspaces, processRegistry,
                sessionRoles, running, instanceId, dataSource, toolResultCache, approvalSource,
                durableApprovalSource);
        retentionService.start();
    }

    @PreDestroy
    void shutdownRetention() {
        if (retentionService != null) retentionService.shutdown();
        if (processRegistry != null) processRegistry.closeAll();
        if (subagentTool != null) subagentTool.close();
    }

    public boolean deleteSession(SessionId id) {
        if (id == null) return false;
        if (running.containsKey(id)) {
            throw new IllegalStateException("session is currently running");
        }
        if (processRegistry != null) processRegistry.closeSession(id);
        if (subagentTool != null) subagentTool.closeSession(id);
        if (toolResultCache != null) {
            try { toolResultCache.deleteBySession(id.value()); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
        }
        boolean deleted = sessionRetention.delete(id);
        sessionRoles.remove(id);
        subagentRegistry.remove(id);
        return deleted;
    }

    public SessionId createSession() {
        return createSession(null, null);
    }

    public SessionId createSession(String agentName) {
        return createSession(agentName, null, "workspace");
    }

    public SessionId createSession(String agentName, String user) {
        return createSession(agentName, user, "workspace");
    }

    public SessionId createSession(String agentName, String user, String mode) {
        return createSession(agentName, user, mode, false);
    }

    public SessionId createSession(String agentName, String user, String mode, boolean retain) {
        return createSession(agentName, user, mode, retain, List.of("*"));
    }

    public SessionId createSession(String agentName, String user, String mode, boolean retain,
                                   List<String> roles) {
        String normalizedUser = (user == null || user.isBlank()) ? "anonymous" : user.trim();
        String normalizedMode = (mode == null || mode.isBlank()) ? "workspace" : mode.trim().toLowerCase();
        // Session-specific (ephemeral) workspaces are only created in serverless mode.
        // In server mode, ephemeral requests fall back to the regular workspace directory.
        if ("ephemeral".equals(normalizedMode) && !isServerlessMode()) {
            normalizedMode = "workspace";
        }
        // Use the fixed workspace base (not the mutable config.workspace()) so that
        // user-specific directories are always relative to the original base,
        // regardless of previous workspace switches.
        Path base = (workspacePath != null ? workspacePath : Path.of(config.workspace())).toAbsolutePath().normalize();
        Path directory = base;
        if (!"opencode".equalsIgnoreCase(normalizedUser) && !"anonymous".equalsIgnoreCase(normalizedUser)
                && !"local".equalsIgnoreCase(normalizedUser)) {
            Path candidate = base.resolve("user-ws").resolve(normalizedUser).normalize();
            if (candidate.startsWith(base)) {
                if (!java.nio.file.Files.isDirectory(candidate)) {
                    try {
                        java.nio.file.Files.createDirectories(candidate);
                    } catch (java.io.IOException ignored) {
                    }
                }
                if (java.nio.file.Files.isDirectory(candidate)) {
                    directory = candidate;
                }
            }
        }
        Location location = new Location(directory, ProjectId.create(), Option.none());
        AgentId selected = resolveAgent(agentName);
        if (selected == null && (agentName == null || agentName.isBlank())
                && "guest".equalsIgnoreCase(normalizedUser)) {
            selected = resolveAgent("guest-reviewer");
        }
        AgentId agent = selected == null ? agentCatalogue.primaryId() : selected;
        Instant now = clock.now();
        SessionId id = sessionService.create(location, agent, Option.none(), now);
        String aName = agentCatalogue.nameOf(agent);
        if (aName == null || aName.isBlank()) aName = "session";
        // Title: Name + HHMMSS + SessionId (for logical caller identification)
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault());
        String ts = fmt.format(now);
        String title = aName + " " + ts;
        sessions.put(new SessionInfo(id, location.projectId(), location, title, agent,
                Option.none(), List.of(), Money.zero(), TokenCount.zero(), now, now,
                Option.none(), Option.none(), 0, Option.none(), normalizedUser));
        if (sessionWorkspaces != null) {
            String root = directory.toString();
            if ("ephemeral".equals(normalizedMode)) {
                Path tempRoot = Path.of(config.sessionTempDir());
                if (!tempRoot.isAbsolute()) tempRoot = base.resolve(tempRoot);
                // Session-specific workspace named directly after the session ID.
                Path sessionDir = tempRoot.toAbsolutePath().normalize().resolve(id.value());
                try {
                    java.nio.file.Files.createDirectories(sessionDir);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("cannot create ephemeral session directory: " + sessionDir, e);
                }
                root = sessionDir.toString();
                sessionWorkspaces.put(id, "ephemeral", "active", root, instanceId, retain);
            } else {
                sessionWorkspaces.put(id, "workspace", "active", root, instanceId, retain);
            }
        }
        sessionRoles.put(id, roles == null ? List.of() : List.copyOf(roles));
        return id;
    }

    private AgentId resolveAgent(String selector) {
        if (selector == null || selector.isBlank()) return null;
        return agentCatalogue.list().stream()
                .filter(agent -> agent.name().equalsIgnoreCase(selector)
                        || agent.id().equalsIgnoreCase(selector))
                .findFirst()
                .map(AgentCatalogue.AgentDto::id)
                .map(AgentId::parse)
                .orElse(null);
    }

    /**
     * Switches the active agent of an existing session. The next prompt uses
     * the new agent's system prompt, model, tools and permissions.
     */
    public Option<AgentId> switchAgent(SessionId id, String agentSelector) {
        if (id == null) throw new IllegalArgumentException("session id must not be null");
        SessionInfo info = sessions.info(id).orElse(null);
        if (info == null) return Option.none();
        if (running.containsKey(id)) {
            throw new IllegalStateException("session is currently running");
        }
        AgentId selected = resolveAgent(agentSelector);
        if (selected == null) {
            throw new IllegalArgumentException("unknown agent: " + agentSelector);
        }
        if (selected.equals(info.agent())) return Option.some(selected);

        sessions.put(new SessionInfo(id, info.projectId(), info.location(), info.title(),
                selected, info.model(), info.permissions(), info.cost(), info.tokens(),
                info.created(), clock.now(), info.idle(), info.suspended(),
                info.resumeAttempts(), info.archived(), info.ownerId()));
        journal.append(new EventDraft(id.value(), 1,
                new SessionEvent.SessionAgentSelected(id, selected, Option.some(info.agent()))));
        return Option.some(selected);
    }

    public Option<SessionInfo> session(SessionId id) {
        return sessions.info(id);
    }

    /** Read-only audit projection over security- and execution-relevant events. */
    public List<AuditEntry> audit(SessionId id, int limit) {
        int max = Math.max(1, Math.min(limit <= 0 ? 100 : limit, 1_000));
        List<StoredEvent> events = journal.history(id.value(), Seq.zero());
        List<AuditEntry> entries = new ArrayList<>();
        for (StoredEvent event : events) {
            String type = event.type();
            if (!type.startsWith("Tool") && !type.startsWith("Compaction")
                    && !type.startsWith("Execution") && !type.startsWith("Step")) {
                continue;
            }
            entries.add(new AuditEntry(event.seq().value(), type, event.time().toString()));
        }
        int from = Math.max(0, entries.size() - max);
        return List.copyOf(entries.subList(from, entries.size()));
    }

    public record AuditEntry(long seq, String type, String time) {}

    public List<PermissionRequest> pendingPermissions(SessionId id) {
        List<PermissionRequest> inMemory = permissionEngine.pending().list(id);
        if (approvalSource == null) return inMemory;
        List<PermissionRequest> merged = new ArrayList<>(inMemory);
        for (de.augmentia.oc.engine.core.port.store.PendingApprovalStore.PendingApproval durable : approvalSource.pending(id.value())) {
            boolean present = inMemory.stream().anyMatch(request ->
                    request.id().value().equals(durable.id()));
            if (present) continue;
            merged.add(new PermissionRequest(
                    PermissionRequestId.parse(durable.id()),
                    SessionId.parse(durable.sessionId()),
                    durable.action(),
                    List.of(durable.context() == null ? durable.action() : durable.context()),
                    List.of(),
                    Map.of("durable", true, "state", durable.state()),
                    null,
                    "ORPHANED".equals(durable.state())
                            ? "orphaned approval from an earlier process"
                            : "pending approval from an earlier process"));
        }
        return List.copyOf(merged);
    }

    public List<PermissionRequest> allPendingPermissions() {
        return permissionEngine.pending().list(null);
    }

    /** Pending permissions for a session plus its direct child sessions. */
    public List<PermissionRequest> pendingPermissionsForChildren(SessionId id) {
        if (id == null) return List.of();
        List<PermissionRequest> result = new ArrayList<>(pendingPermissions(id));
        for (SessionId child : subagentRegistry.children(id)) {
            result.addAll(pendingPermissions(child));
        }
        // Deduplicate by request id.
        Map<String, PermissionRequest> byId = new LinkedHashMap<>();
        for (PermissionRequest r : result) byId.putIfAbsent(r.id().value(), r);
        return List.copyOf(byId.values());
    }

    /** Pending questions for a session plus its direct child sessions. */
    public List<WebQuestionSource.Pending> pendingQuestionsForChildren(SessionId id) {
        if (id == null || questionSource == null) return List.of();
        java.util.Set<SessionId> sessions = new java.util.LinkedHashSet<>();
        sessions.add(id);
        sessions.addAll(subagentRegistry.children(id));
        return questionSource.listPending().stream()
                .filter(p -> p.session() != null && sessions.contains(p.session()))
                .toList();
    }

    public Option<ReplyOutcome> replyPermission(SessionId id, PermissionRequestId requestId,
                                                Reply reply, Option<String> feedback) {
        ProjectId project = sessions.info(id).map(SessionInfo::projectId).orElse(ProjectId.create());
        return permissionEngine.reply(id, requestId, reply, feedback, project);
    }
    public void resolvePermission(SessionId id, PermissionRequestId requestId, Reply reply, Option<String> feedback) {
        LOG.infof("permission reply session=%s request=%s reply=%s", id.value(), requestId.value(), reply);        ApprovalResult result;
        if (reply == Reply.REJECT) {
            result = new ApprovalResult.Denied(feedback == null ? Option.none() : feedback);
        } else {
            result = new ApprovalResult.Approved();
        }
        approvalSource.resolve(id.value(), requestId.value(), result);
    }

    private static final int MAX_SNAPSHOT_FILES = 2_000;
    private static final long MAX_SNAPSHOT_FILE_BYTES = 1_048_576;
    private static final long MAX_SNAPSHOT_TOTAL_BYTES = 20L * 1024 * 1024;

    /**
     * Captures the pre-turn workspace so a later revert can restore it. One
     * snapshot per session (the last turn); capture failures never block the
     * prompt, they only disable file restore for that revert.
     */
    private void purgeDecidedApprovals() {
        Instant cutoff = clock.now().minus(java.time.Duration.ofDays(7));
        int deleted = 0;
        if (approvalSource != null) {
            deleted += approvalSource.deleteDecidedBefore(cutoff);
        }
        if (durableApprovalSource != null) {
            deleted += durableApprovalSource.deleteDecidedBefore(cutoff);
        }
        if (deleted > 0) LOG.infof("purged %d decided approvals", deleted);
    }

    private void purgeToolResultCache() {
        if (toolResultCache == null) return;
        Instant cutoff = clock.now().minus(java.time.Duration.ofDays(1));
        int deleted = toolResultCache.deleteCreatedBefore(cutoff);
        if (deleted > 0) LOG.infof("purged %d tool result cache entries", deleted);
    }

    private void captureSnapshot(SessionId id) {
        if (snapshots == null || !revertSnapshotEnabled) return;
        try {
            List<Path> paths = workspaceFiles();
            if (paths.isEmpty()) return;
            SnapshotId snapshot = snapshots.capture(paths);
            CapturedSnapshot previous = revertSnapshots.put(id, new CapturedSnapshot(snapshot, paths));
            if (previous != null && !previous.id().equals(snapshot)) {
                try {
                    snapshots.delete(previous.id());
                } catch (RuntimeException e) {
                    LOG.warnf("cannot delete previous snapshot %s: %s", previous.id().value(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            revertSnapshots.remove(id);
            LOG.warnf("snapshot capture failed for %s: %s", id.value(), e.getMessage());
        }
    }

    private List<Path> workspaceFiles() {
        Path root = Path.of(config.workspace()).toAbsolutePath().normalize();
        List<Path> paths = new ArrayList<>();
        try (var stream = java.nio.file.Files.walk(root)) {
            stream.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> !snapshotExcluded(root.relativize(path)))
                    .filter(path -> {
                        try {
                            return java.nio.file.Files.size(path) <= MAX_SNAPSHOT_FILE_BYTES;
                        } catch (java.io.IOException e) {
                            return false;
                        }
                    })
                    .limit(MAX_SNAPSHOT_FILES)
                    .forEach(paths::add);
        } catch (java.io.IOException e) {
            LOG.warn("snapshot scan failed: " + e.getMessage());
            return List.of();
        }
        long total = 0;
        for (Path path : paths) {
            try {
                total += java.nio.file.Files.size(path);
            } catch (java.io.IOException e) {
                return List.of();
            }
            if (total > MAX_SNAPSHOT_TOTAL_BYTES) {
                LOG.warn("workspace exceeds the snapshot budget; revert will not restore files");
                return List.of();
            }
        }
        return paths;
    }

    private static boolean snapshotExcluded(Path relative) {
        for (Path part : relative) {
            String name = part.toString();
            if (name.startsWith(".") || name.equals("node_modules") || name.equals("target")
                    || name.equals("data")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reverts the last user turn: restores the pre-turn snapshot, deletes the
     * conversation messages at and after the boundary and commits the revert
     * event. The event log stays append-only.
     */
    public RevertResult revert(SessionId id) {
        if (id == null) return new RevertResult.Failed("session is required");
        if (running.containsKey(id)) throw new IllegalStateException("session is currently running");
        List<Message> messages = conversation.messages(id, Seq.zero());
        MessageId boundary = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.UserMessage user) {
                boundary = user.id();
                break;
            }
        }
        if (boundary == null) return new RevertResult.Failed("no user message to revert");
        Option<Seq> seq = conversation.seqOf(id, boundary);
        if (seq.isEmpty()) return new RevertResult.Failed("revert boundary not found");

        CapturedSnapshot captured = revertSnapshots.remove(id);
        Option<SnapshotId> snapshot = captured == null ? Option.none() : Option.some(captured.id());
        List<Path> paths = captured == null ? List.of() : captured.paths();
        RevertResult result = revert.commit(id, boundary, snapshot, paths);
        if (result instanceof RevertResult.Failed) {
            if (captured != null) revertSnapshots.put(id, captured);
            return result;
        }
        conversation.deleteFrom(id, seq.orElseThrow());
        if (captured != null) {
            try {
                snapshots.delete(captured.id());
            } catch (RuntimeException e) {
                LOG.warnf("cannot delete revert snapshot %s: %s", captured.id().value(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Estimates the context tokens that the next step would send for this
     * session. Intended for diagnostics and end-to-end compaction tests.
     */
    public int contextTokens(SessionId id) {
        if (id == null) throw new IllegalArgumentException("session id must not be null");
        SessionInfo info = sessions.info(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown session: " + id));
        String agentName = agentCatalogue.nameOf(info.agent());
        EngineConfig.AgentConfig agentConfig = agentCatalogue.config(agentName);
        AgentProfile agent = agentProfile(info.agent(), agentName, agentConfig);
        ToolSnapshot snapshot = filterToolSnapshot(toolSnapshot, agentConfig);
        Prepared prepared = contextBuilder.prepare(info, agent, modelInfo(), snapshot,
                Seq.zero(), 1);
        return ContextSize.estimate(prepared);
    }

    /** Runs one manual compaction pass (keeps the last messages). */
    public de.augmentia.oc.engine.runtime.compaction.CompactionResult compact(SessionId id) {
        if (id == null) throw new IllegalArgumentException("session id must not be null");
        if (running.containsKey(id)) throw new IllegalStateException("session is currently running");
        SessionInfo info = sessions.info(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown session: " + id));
        if (!compaction.hasWork(id)) {
            return new de.augmentia.oc.engine.runtime.compaction.CompactionResult.NothingToDo();
        }
        ModelRef model = preparedModel(info, modelInfo());
        Seq throughSeq = Seq.of(journal.history(id.value(), Seq.zero()).size());
        return compaction.compact(id, CompactionReason.MANUAL, model,
                modelInfo().limits().context(), Option.none(), throughSeq, clock.now());
    }

    /** Background shell processes of a session. */
    public List<ProcessRegistry.Snapshot> processes(SessionId id) {
        return processRegistry == null ? List.of() : processRegistry.list(id);
    }

    public boolean killProcess(SessionId id, String processId) {
        return processRegistry != null && processRegistry.kill(id, processId);
    }

    /** Background subagent tasks of a session. */
    public List<BackgroundTasks.Snapshot> tasks(SessionId id) {
        return subagentTool == null ? List.of() : subagentTool.backgroundTasks().list(id);
    }

    public boolean cancelTask(SessionId id, String taskId) {
        return subagentTool != null && subagentTool.backgroundTasks().cancel(id, taskId);
    }

    /**
     * Message tree of a session. QOC sessions are linear; a fork creates a
     * separate session with copied messages up to the boundary.
     */
    public SessionTree tree(SessionId id) {
        if (id == null) throw new IllegalArgumentException("session id must not be null");
        List<Message> messages = conversation.messages(id, Seq.zero());
        List<SessionTreeEntry> entries = new ArrayList<>();
        MessageId previous = null;
        for (Message message : messages) {
            Option<Seq> seq = conversation.seqOf(id, message.id());
            String parent = conversation.parentOf(id, message.id())
                    .map(MessageId::value)
                    .orElse(previous == null ? "" : previous.value());
            entries.add(new SessionTreeEntry(message.id().value(), parent,
                    seq.map(Seq::value).orElse(0L),
                    message.getClass().getSimpleName(), treeText(message)));
            previous = message.id();
        }
        String leaf = messages.isEmpty() ? "" : messages.getLast().id().value();
        String parentSession = parentSession(id);
        return new SessionTree(id.value(), parentSession, leaf, entries);
    }

    /**
     * Creates a new session containing a copy of the parent messages through
     * {@code boundary}. The child gets fresh message ids and its own parent
     * chain; the parent is not mutated.
     */
    public SessionId fork(SessionId parentId, String boundary, String directory, String agentSelector) {
        if (parentId == null || boundary == null || boundary.isBlank()) {
            throw new IllegalArgumentException("session and boundary must be set");
        }
        if (running.containsKey(parentId)) {
            throw new IllegalStateException("session is currently running");
        }
        SessionInfo parent = sessions.info(parentId)
                .orElseThrow(() -> new IllegalArgumentException("unknown session: " + parentId));
        MessageId boundaryId = MessageId.parse(boundary);
        if (conversation.message(parentId, boundaryId).isEmpty()) {
            throw new IllegalArgumentException("fork boundary not found: " + boundary);
        }
        List<Message> history = conversation.messages(parentId, Seq.zero());
        int boundaryIndex = -1;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).id().equals(boundaryId)) {
                boundaryIndex = i;
                break;
            }
        }
        if (boundaryIndex < 0) throw new IllegalArgumentException("fork boundary not found: " + boundary);

        AgentId agent = resolveAgent(agentSelector);
        if (agent == null) agent = parent.agent();
        Location location = forkLocation(parent.location(), directory);
        Instant now = clock.now();
        SessionId child = sessionService.create(location, agent, parent.model(), now);
        sessions.put(new SessionInfo(child, location.projectId(), location,
                "Fork of " + parent.title(), agent, parent.model(), parent.permissions(),
                Money.zero(), TokenCount.zero(), now, now, Option.none(), Option.none(), 0, Option.none(), parent.ownerId()));

        MessageId previous = null;
        Map<String, String> copiedArtifacts = new java.util.HashMap<>();
        for (int i = 0; i <= boundaryIndex; i++) {
            Message copy = copyMessage(history.get(i), MessageId.create(), child, copiedArtifacts);
            conversation.insertWithParent(child, Seq.of(i + 1), copy,
                    previous == null ? Option.none() : Option.some(previous));
            previous = copy.id();
        }
        journal.append(new EventDraft(child.value(), 1,
                new SessionEvent.SessionForked(child, parentId, new ForkBoundary(boundaryId))));
        return child;
    }

    private Location forkLocation(Location parent, String directory) {
        if (directory == null || directory.isBlank()) return parent;
        Path target = Path.of(directory);
        if (!target.isAbsolute()) target = Path.of(config.workspace()).resolve(directory);
        return new Location(target.toAbsolutePath().normalize(), parent.projectId(), parent.workspaceId());
    }

    private String parentSession(SessionId id) {
        return journal.historyOfType(id.value(), "SessionForked", Seq.zero()).stream()
                .filter(event -> event.payload() instanceof SessionEvent.SessionForked)
                .map(event -> ((SessionEvent.SessionForked) event.payload()).parentId().value())
                .findFirst()
                .orElse("");
    }

    private Message copyMessage(Message message, MessageId id, SessionId child,
                                Map<String, String> copiedArtifacts) {
        return switch (message) {
            case Message.UserMessage user -> new Message.UserMessage(id, user.text(), user.files(),
                    user.agents(), user.skills(), user.metadata(), user.time());
            case Message.SyntheticMessage synthetic -> new Message.SyntheticMessage(id, synthetic.text(),
                    synthetic.description(), synthetic.metadata(), synthetic.time());
            case Message.SystemMessage system -> new Message.SystemMessage(id, system.text(),
                    system.description(), system.metadata(), system.time());
            case Message.SkillMessage skill -> new Message.SkillMessage(id, skill.skill(), skill.name(),
                    skill.text(), skill.metadata(), skill.time());
            case Message.ShellMessage shell -> new Message.ShellMessage(id, shell.shell(), shell.time());
            case Message.AssistantMessage assistant -> new Message.AssistantMessage(id, assistant.agent(),
                    assistant.model(), copyContent(assistant.content(), child, copiedArtifacts),
                    assistant.finish(), assistant.error(), assistant.retry(), assistant.snapshot(),
                    assistant.cost(), assistant.tokens(), assistant.assistantTime());
            case Message.CompactionMessage compaction -> new Message.CompactionMessage(id,
                    compaction.status(), compaction.reason(), compaction.summary(), compaction.error(),
                    compaction.cost(), compaction.tokens(), compaction.time());
            case Message.AgentSwitched switched -> new Message.AgentSwitched(id, switched.agent(),
                    switched.previous(), switched.time());
            case Message.ModelSwitched switched -> new Message.ModelSwitched(id, switched.model(),
                    switched.previous(), switched.time());
            case Message.LocationSwitched switched -> new Message.LocationSwitched(id, switched.location(),
                    switched.previous(), switched.time());
            case Message.IdleMessage idle -> new Message.IdleMessage(id, idle.outcome(), idle.error(),
                    idle.time());
        };
    }

    private List<Content> copyContent(List<Content> content, SessionId child,
                                      Map<String, String> copiedArtifacts) {
        List<Content> copied = new ArrayList<>();
        for (Content item : content) {
            if (item instanceof Content.ToolCardContent cardContent) {
                copied.add(new Content.ToolCardContent(copyCard(cardContent.card(), child, copiedArtifacts)));
            } else {
                copied.add(item);
            }
        }
        return copied;
    }

    private ToolCard copyCard(ToolCard card, SessionId child, Map<String, String> copiedArtifacts) {
        ToolState state = switch (card.state()) {
            case ToolState.Completed completed -> new ToolState.Completed(completed.input(),
                    copyToolContent(completed.content(), child, copiedArtifacts),
                    rewriteMetadata(completed.metadata(), child, copiedArtifacts));
            case ToolState.Failed failed -> new ToolState.Failed(failed.input(), failed.error(),
                    failed.content().map(items -> copyToolContent(items, child, copiedArtifacts)),
                    rewriteMetadata(failed.metadata(), child, copiedArtifacts));
            case ToolState.Running running -> new ToolState.Running(running.input(),
                    rewriteMetadata(running.metadata(), child, copiedArtifacts));
            case ToolState.Streaming ignored -> card.state();
        };
        return new ToolCard(card.id(), card.name(), card.input(), state, card.executed(), card.time());
    }

    private List<ToolContent> copyToolContent(List<ToolContent> content, SessionId child,
                                              Map<String, String> copiedArtifacts) {
        List<ToolContent> copied = new ArrayList<>();
        for (ToolContent item : content) {
            if (item instanceof ToolContent.FileItem file) {
                copied.add(new ToolContent.FileItem(
                        copyArtifactUri(file.uri(), child, copiedArtifacts), file.mime(), file.name()));
            } else {
                copied.add(item);
            }
        }
        return copied;
    }

    private Map<String, Object> rewriteMetadata(Map<String, Object> metadata, SessionId child,
                                                Map<String, String> copiedArtifacts) {
        Map<String, Object> rewritten = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text && text.startsWith(ArtifactRef.SCHEME)) {
                rewritten.put(entry.getKey(), copyArtifactUri(text, child, copiedArtifacts));
            } else {
                rewritten.put(entry.getKey(), value);
            }
        }
        return rewritten;
    }

    private String copyArtifactUri(String uri, SessionId child, Map<String, String> copiedArtifacts) {
        if (!uri.startsWith(ArtifactRef.SCHEME) || toolOutput == null) return uri;
        String cached = copiedArtifacts.get(uri);
        if (cached != null) return cached;
        try {
            ArtifactRef.Parsed parsed = ArtifactRef.parse(uri);
            if (parsed.session().equals(child)) return uri;
            if (toolOutput.find(parsed.session(), parsed.id()).isEmpty()) return uri;
            ArtifactRef copy = toolOutput.copy(parsed.session(), child, parsed.id());
            copiedArtifacts.put(uri, copy.uri());
            return copy.uri();
        } catch (RuntimeException e) {
            LOG.warnf("cannot copy artifact %s into fork %s: %s", uri, child.value(), e.getMessage());
            return uri;
        }
    }

    private static String treeText(Message message) {
        return switch (message) {
            case Message.UserMessage user -> user.text();
            case Message.SyntheticMessage synthetic -> synthetic.text();
            case Message.SystemMessage system -> system.text();
            case Message.SkillMessage skill -> skill.text();
            case Message.ShellMessage shell -> shell.shell().command();
            case Message.AssistantMessage assistant -> assistant.content().stream()
                    .filter(item -> item instanceof Content.TextContent)
                    .map(item -> ((Content.TextContent) item).text())
                    .reduce("", String::concat);
            case Message.CompactionMessage compaction -> compaction.summary();
            case Message.AgentSwitched switched -> "agent switched to " + switched.agent();
            case Message.ModelSwitched switched -> "model switched to " + switched.model();
            case Message.LocationSwitched ignored -> "location switched";
            case Message.IdleMessage idle -> "idle: " + idle.outcome();
        };
    }

    /** One message in the session tree projection. */
    public record SessionTreeEntry(String id, String parentId, long seq, String type, String text) {}

    /** Tree read model; a fork links to its parent session through the event log. */
    public record SessionTree(String session, String parentSession, String leaf,
                              List<SessionTreeEntry> entries) {
        public SessionTree {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** Renames a session and records the event. */
    public String renameSession(SessionId id, String title) {
        if (id == null || title == null || title.isBlank()) {
            throw new IllegalArgumentException("session and title must be set");
        }
        SessionInfo info = sessions.info(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown session: " + id));
        journal.append(new EventDraft(id.value(), 1, new SessionEvent.SessionRenamed(id, title)));
        sessions.put(copy(info, info.location(), title));
        return title;
    }

    /**
     * Moves the session's location metadata. The process keeps one workspace
     * (L-A); tools still resolve against the process workspace.
     */
    public String moveSession(SessionId id, String directory) {
        if (id == null || directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("session and directory must be set");
        }
        SessionInfo info = sessions.info(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown session: " + id));
        Path target = Path.of(directory);
        if (!target.isAbsolute()) target = Path.of(config.workspace()).resolve(directory);
        target = target.toAbsolutePath().normalize();
        Location location = new Location(target, info.projectId(), Option.none());
        journal.append(new EventDraft(id.value(), 1, new SessionEvent.SessionMoved(id, location)));
        sessions.put(copy(info, location, info.title()));
        return target.toString();
    }

    public List<SessionTools.ModelSummary> modelSummaries() {
        return List.of(new SessionTools.ModelSummary(config.model(), config.model(), config.provider()));
    }

    public List<TodoTool.Todo> todos(SessionId id) {
        return todoTool == null ? List.of() : todoTool.todos(id);
    }

    public Option<WebQuestionSource.Pending> pendingQuestion(SessionId id) {
        return questionSource != null ? questionSource.pending(id) : Option.none();
    }

    public boolean replyQuestion(SessionId id, String questionId, List<String> selected, String text) {
        return questionSource.resolve(id, questionId, selected, text);
    }

    /** Liveness is process-local; readiness also checks DB and workspace. */
    public boolean ready() {
        if (!memoryMode && !de.augmentia.oc.engine.adapter.sql.DbHealthSql.isHealthy(dataSource, 2)) {
            LOG.warnf("readiness: database check failed");
            return false;
        }
        try {
            return java.nio.file.Files.isDirectory(
                    Path.of(config.workspace()).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            LOG.warnf("readiness: workspace check failed: %s", e.getMessage());
            return false;
        }
    }

    private static SessionInfo copy(SessionInfo info, Location location, String title) {
        return new SessionInfo(info.id(), info.projectId(), location, title, info.agent(),
                info.model(), info.permissions(), info.cost(), info.tokens(), info.created(),
                Instant.now(), info.idle(), info.suspended(), info.resumeAttempts(), info.archived(), info.ownerId());
    }

    public String generateTitle(SessionId id) {
        if (titleGenerator == null) return null;
        SessionInfo info = sessions.info(id).orElse(null);
        if (info == null) return null;
        List<Message> messages = conversation.messages(id, Seq.zero());
        if (messages.isEmpty()) return null;
        String title = titleGenerator.fromConversation(messages, id, info.projectId());
        // Update the session title via a new event.
        journal.append(new EventDraft(id.value(), 1,
                new SessionEvent.SessionRenamed(id, title)));
        sessions.put(new SessionInfo(info.id(), info.projectId(), info.location(), title,
                info.agent(), info.model(), info.permissions(), info.cost(), info.tokens(),
                info.created(), clock.now(), info.idle(), info.suspended(),
                info.resumeAttempts(), info.archived(), info.ownerId()));
        return title;
    }

    public PromptResult command(SessionId id, String name, String arguments) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("command name must not be blank");
        }
        String args = arguments == null ? "" : arguments;
        Option<String> expanded = commandLoader == null ? Option.none()
                : commandLoader.expand(name, args);
        return prompt(id, expanded.orElse("/" + name + (args.isBlank() ? "" : " " + args)),
                Option.none());
    }

    public PromptResult prompt(SessionId id, String text) {
        return prompt(id, text, Option.none());
    }

    public PromptResult prompt(SessionId id, String text, Option<Schema> responseSchema) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        LOG.infof("prompt start session=%s text=%s", id.value(), text.length() > 120 ? text.substring(0,120) + "..." : text);
        Option<SessionInfo> found = sessions.info(id);
        if (found.isEmpty()) throw new IllegalArgumentException("unknown session: " + id);

        long before = journal.history(id.value(), Seq.zero()).size();
        metrics.promptStarted();
        if (!promptSlots.tryAcquire()) {
            metrics.promptBusy();
            LOG.warnf("prompt rejected: engine saturated session=%s", id.value());
            return PromptResult.busy("engine is saturated; retry later");
        }
        Thread existing = running.putIfAbsent(id, Thread.currentThread());
        if (existing != null) {
            promptSlots.release();
            metrics.promptBusy();
            LOG.infof("prompt busy session=%s", id.value());
            return PromptResult.busy("session is currently running");
        }
        Span span = tracer().spanBuilder("engine.prompt")
                .setAttribute("session.id", id.value())
                .setAttribute("text.length", (long) text.length())
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            try {
                captureSnapshot(id);
                PromptResult r = runPrompt(id, text, null, responseSchema);
                span.setAttribute("finish", r.finish());
                span.setAttribute("usage.input_tokens", (long) r.inputTokens());
                span.setAttribute("usage.output_tokens", (long) r.outputTokens());
                switch (r.outcome()) {
                    case TurnOutcome.Completed ignored -> metrics.promptCompleted();
                    case TurnOutcome.Busy ignored -> metrics.promptBusy();
                    case TurnOutcome.Refused ignored -> metrics.promptFailed();
                    case TurnOutcome.Failed ignored -> metrics.promptFailed();
                    case TurnOutcome.Terminated ignored -> metrics.promptCancelled();
                    case TurnOutcome.Cancelled ignored -> metrics.promptCancelled();
                    case TurnOutcome.Paused ignored -> metrics.promptCompleted();
                }
                LOG.infof("prompt finish session=%s outcome=%s finish=%s in=%d out=%d", id.value(),
                        r.outcome().getClass().getSimpleName(), r.finish(), r.inputTokens(), r.outputTokens());
                return r;
            } catch (IllegalArgumentException e) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR, e.getMessage());
                throw e;
            } catch (de.augmentia.oc.engine.core.error.Aborted e) {
                span.setAttribute("outcome", "CANCELLED");
                metrics.promptCancelled();
                LOG.infof("prompt cancelled session=%s reason=%s", id.value(), e.getMessage());
                return PromptResult.cancelled();
            } catch (RuntimeException e) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR, e.getMessage());
                LOG.errorf(e, "prompt failed session=%s", id.value());
                metrics.promptFailed();
                if (Thread.currentThread().isInterrupted()) {
                    metrics.promptCancelled();
                    return PromptResult.cancelled();
                }
                return PromptResult.failed(new SessionError.Unknown("prompt_failed",
                        e.getMessage() == null ? "prompt failed" : e.getMessage()), false);
            } finally {
                running.remove(id, Thread.currentThread());
                promptSlots.release();
                publishNew(id, before);
            }
        } finally {
            span.end();
        }
    }

    public PromptResult runPrompt(SessionId id, String text) {
        return runPrompt(id, text, null, Option.none());
    }

    /** Resumes a paused session after an approval was granted. */
    public PromptResult resume(SessionId id) {
        if (id == null) throw new IllegalArgumentException("session id must not be null");
        // Instance affinity / claim-based takeover.
        if (sessionWorkspaces != null) {
            String owner = sessionWorkspaces.instanceId(id);
            if (owner != null && !owner.isBlank() && !owner.equals(instanceId)) {
                // Allow takeover if the owning instance hasn't updated the session
                // recently (lease expired).
                SessionInfo cur = sessions.info(id).orElse(null);
                boolean leaseExpired = cur == null || cur.updated().isBefore(
                        clock.now().minus(java.time.Duration.ofMinutes(5)));
                if (!leaseExpired) {
                    throw new IllegalStateException("session belongs to another instance: " + owner);
                }
                LOG.infof("taking over paused session %s from instance %s", id.value(), owner);
                sessionWorkspaces.put(id, sessionWorkspaces.executionMode(id),
                        sessionWorkspaces.state(id), sessionWorkspaces.workspaceRoot(id),
                        instanceId, sessionWorkspaces.retain(id));
            }
        }
        String text = pausedPrompts.get(id);
        if (text == null || text.isBlank()) {
            if (sessionWorkspaces != null) text = sessionWorkspaces.pausedPrompt(id);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("session is not paused: " + id.value());
        }
        PromptResult result = runPrompt(id, text);
        pausedPrompts.remove(id);
        if (sessionWorkspaces != null) {
            try { sessionWorkspaces.setPausedPrompt(id, null); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
            try { sessionWorkspaces.updateState(id, "active"); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
        }
        return result;
    }

    public PromptResult runPrompt(SessionId id, String text, AgentProfile agentOverride) {
        return runPrompt(id, text, agentOverride, Option.none());
    }

    public PromptResult runPrompt(SessionId id, String text, AgentProfile agentOverride,
                                  Option<Schema> responseSchema) {
        if (responseSchema == null) responseSchema = Option.none();
        Schema resultSchema = responseSchema.orElse(null);

        // Validate the reserved result-tool name before touching the inbox so a
        // rejected schema request does not leave a queued item behind.
        ToolSnapshot runSnapshot = toolSnapshot;
        Step runStep = step;
        Path ephemeralRoot = null;
        if (sessionWorkspaces != null && "ephemeral".equals(sessionWorkspaces.executionMode(id))) {
            String root = sessionWorkspaces.workspaceRoot(id);
            if (root != null && !root.isBlank()) ephemeralRoot = Path.of(root);
        }
        if (resultSchema != null) {
            ToolSnapshot base = ephemeralRoot == null ? toolSnapshot
                    : buildEphemeralBaseSnapshot(ephemeralRoot);
            runSnapshot = SubmitResult.withTool(base, resultSchema);
        } else if (ephemeralRoot != null) {
            runSnapshot = buildEphemeralBaseSnapshot(ephemeralRoot);
        }

        InboxId inboxId = InboxId.create();
        CorrelationContext.set(inboxId.value(), id.value());
        org.jboss.logmanager.MDC.put("correlation_id", inboxId.value());
        org.jboss.logmanager.MDC.put("session_id", id.value());
        InboxItem item = new InboxItem.UserItem(text, List.of(), List.of(), List.of(), Map.of());
        sessionService.enqueue(id, inboxId, item);
        Seq enqueuedSeq = Seq.of(journal.history(id.value(), Seq.zero()).size());
        sessions.putInbox(new InboxEntry(inboxId, id, item,
                de.augmentia.oc.engine.core.model.Delivery.QUEUE, enqueuedSeq, clock.now()));
        sessionService.deliver(id);
        SessionInfo info = sessions.info(id).orElseThrow();
        sessions.put(new SessionInfo(info.id(), info.projectId(), info.location(), info.title(),
                info.agent(), info.model(), info.permissions(), info.cost(), info.tokens(),
                info.created(), clock.now(), info.idle(), info.suspended(),
                info.resumeAttempts(), info.archived(), info.ownerId()));
        info = sessions.info(id).orElseThrow();
        LOG.debugf("runPrompt session=%s agent=%s", id.value(), info.agent());

        Seq seq = conversation.nextSeq(id);
        conversation.insert(id, seq, new Message.UserMessage(MessageId.create(), text,
                List.of(), List.of(), List.of(), Map.of(), clock.now()));

        String agentName = agentCatalogue.nameOf(info.agent());
        EngineConfig.AgentConfig agentConfig = agentCatalogue.config(agentName);
        AgentProfile agent = agentOverride != null ? agentOverride
                : agentProfile(info.agent(), agentName, agentConfig);
        SkillSelection skillSelection = skillSelection(agentName, text);

        // Model override from agent config
        ModelInfo model = modelInfo();
        if (agentConfig != null && agentConfig.model() != null && !agentConfig.model().isBlank()) {
            model = new ModelInfo(ModelId.of(agentConfig.model()), model.providerId(), model.variants(),
                    model.limits(), model.cost(), model.capabilities(), model.compaction(), model.transport());
        }

        // Agent-specific tool snapshot
        ToolSnapshot agentToolSnapshot = filterToolSnapshot(runSnapshot, agentConfig);
        // Subagents must not ask the user questions; the orchestrator handles
        // them if they arise anyway.
        if (subagentDepths.containsKey(id)) {
            java.util.Map<de.augmentia.oc.engine.core.model.ToolName, de.augmentia.oc.engine.core.contract.tool.ToolDefinition> filtered =
                    new java.util.LinkedHashMap<>();
            for (de.augmentia.oc.engine.core.contract.tool.ToolDefinition def : agentToolSnapshot.list()) {
                if (!"question".equalsIgnoreCase(def.effectiveName())) {
                    filtered.put(def.name(), def);
                }
            }
            agentToolSnapshot = new ToolSnapshot(filtered);
        }

        if (resultSchema != null) {
            PermissionGate structuredGate = request -> {
                if (!SubmitResult.TOOL_NAME.equals(request.action())) {
                    permissionGate.assertAllowed(request);
                }
            };
            runStep = new Step(provider, new ToolRuntime(agentToolSnapshot,
                    effectiveAuthorizer, structuredGate, toolHooks, toolOutput, toolResultCache, stepTimeoutSeconds));
        } else if (agentToolSnapshot != runSnapshot) {
            runStep = new Step(provider, new ToolRuntime(agentToolSnapshot,
                    effectiveAuthorizer, permissionGate, toolHooks, toolOutput, toolResultCache, stepTimeoutSeconds));
        }

        // MODE-PLAN: log synthetic message when entering/leaving plan mode (PR-7).
        if (agent.planMode()) {
            journal.append(new EventDraft(id.value(), 1,
                    new SessionEvent.SyntheticRaised(id,
                            PromptLoader.get("mode-plan-enter"), Option.none(), Map.of("mode", "plan"))));
        }

        var liveSink = (java.util.function.Consumer<de.augmentia.oc.engine.core.contract.provider.ModelEvent>) event ->
                eventBus.publishLive(id.value(), Json.encode(ContractCodec.encode(event,
                        de.augmentia.oc.engine.core.contract.provider.ModelEvent.class)));

        int llmCallNo = llmCallCounts.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicInteger()).getAndIncrement();
        java.util.concurrent.atomic.AtomicReference<String> llmResponseSummary =
                new java.util.concurrent.atomic.AtomicReference<>();

        int stepNo = 1;
        StepResult.Finished finished = null;
        FinishReason finishReason = FinishReason.NORMAL;
        TokenCount usage = TokenCount.zero();
        String finalText = "";
        Option<JsonValue> structured = Option.none();
        boolean submitForced = false;
        // maxSteps == 0 means no step limit; tool-call and wall-time budgets
        // still apply. A token budget of 0 disables the token limit.
        int stepBudget = agent.maxSteps() <= 0 || agent.maxSteps() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : agent.maxSteps() + 1;
        de.augmentia.oc.engine.core.eval.TurnPolicy turnPolicy =
                new de.augmentia.oc.engine.core.eval.TurnPolicy(
                        stepBudget,
                        Math.max(1, maxTurnToolCalls), Math.max(0, maxTurnTokens),
                        java.time.Duration.ofMinutes(Math.max(1, maxTurnWallMinutes)));
        int totalToolCalls = 0;
        long totalTokens = 0;
        java.util.List<de.augmentia.oc.engine.core.contract.tool.ToolCall> recentToolCalls = new java.util.ArrayList<>();
        java.time.Instant turnStarted = clock.now();
        int retryAttempts = 0;

        // Phase 1: Pre-flight checks
        if (preflightEnabled && preflightChecker != null) {
            var preflightCtx = new de.augmentia.oc.engine.runtime.resilience.PreflightChecker.PreflightContext(
                    config != null ? config.url() : null,
                    providerApiKey,
                    () -> memoryMode || de.augmentia.oc.engine.adapter.sql.DbHealthSql.isHealthy(dataSource, 3),
                    workspacePath);
            var preflightResult = preflightChecker.check(preflightCtx);
            if (!preflightResult.passed()) {
                StringBuilder msg = new StringBuilder("Pre-flight checks failed:\n");
                for (var issue : preflightResult.issues()) {
                    msg.append("  - ").append(issue.type()).append(": ").append(issue.message()).append("\n");
                }
                LOG.warn(msg.toString());
                if (webhookAlerter != null) {
                    webhookAlerter.alert(new de.augmentia.oc.engine.runtime.resilience.WebhookAlerter.Alert(
                            id.value(), "preflight", msg.toString(), null, List.of(), java.time.Instant.now()));
                }
                SessionError error = new SessionError.ProviderError("preflight", msg.toString(), null, null, null);
                CorrelationContext.clear();
        org.jboss.logmanager.MDC.clear();
        return PromptResult.failed(error, false);
            }
        }

        // Delegate core execution to QocEngine (BaseEngine) — no second loop
        de.augmentia.oc.engine.runtime.QocEngine.AgentConfig qocConfig = null;
        if (agentName != null) {
            EngineConfig.AgentConfig cfg = agentCatalogue.config(agentName);
            if (cfg != null) {
                qocConfig = new de.augmentia.oc.engine.runtime.QocEngine.AgentConfig(
                        agentName, cfg.model(), 0.3, cfg.maxSteps() > 0 ? cfg.maxSteps() : 4096, 1.0,
                        null, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), false);
            }
        }
        if (qocConfig == null) {
            qocConfig = new de.augmentia.oc.engine.runtime.QocEngine.AgentConfig(
                    agentName == null ? "build" : agentName, null, 0.3, 4096, 1.0, null,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), false);
        }

        de.augmentia.oc.engine.core.model.Principal principal =
                principalContext == null ? de.augmentia.oc.engine.core.model.Principal.anonymous()
                        : new de.augmentia.oc.engine.core.model.Principal(
                                principalContext.get().id(),
                                principalContext.get().name(),
                                principalContext.get().roles(),
                                principalContext.get().groups(),
                                principalContext.get().claims(),
                                principalContext.get().accessToken());
        de.augmentia.oc.engine.core.model.EngineResult qocResult =
                qocEngine.runPrompt(id, agentName == null ? "build" : agentName, text, qocConfig, false, null, null, principal);

        // Map EngineResult to Quarkus types
        String qocFinalText = qocResult.text();
        String qocFinish = qocResult.finish();
        long qocInputTokens = qocResult.inputTokens();
        long qocOutputTokens = qocResult.outputTokens();
        FinishReason qocFinishReason = FinishReason.NORMAL;
        if ("FAILED".equals(qocFinish)) qocFinishReason = FinishReason.ABORTED;
        TokenCount qocUsage = TokenCount.of(qocInputTokens, qocOutputTokens);
        de.augmentia.oc.engine.core.model.Option<de.augmentia.oc.engine.core.json.JsonValue> qocStructured = qocResult.structured();
        // Assign to existing variables
        finalText = qocFinalText;
        finishReason = qocFinishReason;
        usage = qocUsage;
        structured = qocStructured;


        TurnOutcome outcome = switch (finishReason) {
            case CONTENT_FILTER -> new TurnOutcome.Refused("content_filter",
                    "provider stopped the turn because of a content filter");
            case ABORTED -> new TurnOutcome.Cancelled();
            default -> new TurnOutcome.Completed(finishReason.name(), usage.input(),
                    usage.output(), finalText, structured);
        };
        PromptResult promptResult = new PromptResult(outcome, finishReason.name(),
                usage.input(), usage.output(), finalText, structured);

        // Persist idle outcome so the session is marked as completed/cancelled/...
        try {
            String idleOutcome = outcome instanceof TurnOutcome.Completed ? "completed"
                    : outcome instanceof TurnOutcome.Cancelled ? "cancelled"
                    : outcome instanceof TurnOutcome.Paused ? "paused"
                    : outcome instanceof TurnOutcome.Refused ? "refused"
                    : outcome instanceof TurnOutcome.Failed ? "failed"
                    : "completed";
            sessions.setIdleOutcome(id, idleOutcome);
        } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }

        // Generate title after the first successful prompt if not yet set.
        // Generate title after the first successful prompt if not yet set.
        // Generate title after the first successful prompt if not yet set
        // (only if the title still contains the session ID, i.e. auto-generated)
        if (info.title().matches(".*\\d{2}:\\d{2}:\\d{2}$") && !info.title().startsWith("Generated")) {
            generateTitle(id);
        }

        // Clean up empty sessions (no assistant messages produced).
        try {
            if (conversation.messages(id, Seq.zero()).size() <= 1) {  // only user message, no assistant response
                sessionRetention.delete(id);
                LOG.infof("deleted empty session %s", id.value());
            }
        } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }

        // Ephemeral one-shot cleanup.
        if (sessionWorkspaces != null && "ephemeral".equals(sessionWorkspaces.executionMode(id))) {
            if (!sessionWorkspaces.retain(id)) {
                try { sessionWorkspaces.updateState(id, "completed"); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                if (processRegistry != null) {
                    try { processRegistry.closeSession(id); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                }
                if (toolResultCache != null) {
                    try { toolResultCache.deleteBySession(id.value()); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                }
                try { sessionRetention.delete(id); } catch (RuntimeException e) {
                    LOG.warn("ephemeral session delete failed: " + e.getMessage());
                    try { sessionWorkspaces.updateState(id, "cleanup_pending"); } catch (RuntimeException inner) { LOG.debugf("ignored: %s", inner.getMessage()); }
                }
                String root = sessionWorkspaces.workspaceRoot(id);
                if (root != null && !root.isBlank()) {
                    try { deleteDirectoryRecursively(Path.of(root)); } catch (RuntimeException e) {
                        LOG.warn("ephemeral dir delete failed: " + e.getMessage());
                    }
                }
                try { sessionWorkspaces.deleteWorkspace(id); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                sessionRoles.remove(id);
            } else {
                try { sessionWorkspaces.updateState(id, "completed"); } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
            }
        }
        CorrelationContext.clear();
        org.jboss.logmanager.MDC.clear();
        return promptResult;
    }

    private final java.util.Map<SessionId, Integer> subagentDepths = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int MAX_CONCURRENT_SUBAGENTS = 4;
    private static final java.util.Set<String> WORKSPACE_TOOL_NAMES = java.util.Set.of(
            "read", "write", "edit", "patch", "glob", "grep", "shell",
            "worktree_create", "worktree_list", "worktree_remove");
    private final java.util.concurrent.Semaphore subagentSemaphore = new java.util.concurrent.Semaphore(MAX_CONCURRENT_SUBAGENTS);

    private static String providerErrorType(de.augmentia.oc.engine.core.contract.provider.FailureKind kind) {
        if (kind == null) return "anbieter.unbekannt";
        return switch (kind) {
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.Transport t -> "anbieter.transport";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.RateLimited ignored -> "anbieter.ratenbegrenzung";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.ProviderInternal ignored -> "anbieter.intern";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.Timeout ignored -> "anbieter.zeitueberschreitung";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.Authentication ignored -> "anbieter.auth";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.QuotaExceeded ignored -> "anbieter.kontingent";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.ContentPolicy ignored -> "anbieter.inhaltsfilter";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.UnsupportedOperation ignored -> "anbieter.nicht-unterstuetzt";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.NoRoute ignored -> "anbieter.keine-route";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.UnknownProvider ignored -> "anbieter.unbekannt";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.InvalidOutput ignored -> "anbieter.ungueltige-ausgabe";
            case de.augmentia.oc.engine.core.contract.provider.FailureKind.InvalidRequest ignored -> "anbieter.ungueltige-anfrage";
            default -> "anbieter.unbekannt";
        };
    }

    private static de.augmentia.oc.engine.core.contract.permission.AskMode parseAskMode(String value) {
        if (value == null || value.isBlank()) return de.augmentia.oc.engine.core.contract.permission.AskMode.DENY;
        return switch (value.trim().toLowerCase()) {
            case "allow" -> de.augmentia.oc.engine.core.contract.permission.AskMode.ALLOW;
            case "interactive" -> de.augmentia.oc.engine.core.contract.permission.AskMode.INTERACTIVE;
            default -> de.augmentia.oc.engine.core.contract.permission.AskMode.DENY;
        };
    }

    AgentProfile agentProfile(AgentId id, String name, EngineConfig.AgentConfig config) {
        // Agent-specific rules override defaults (last matching rule wins).
        List<Rule> rules = new ArrayList<>(ContextBuilder.defaults(permissionDefault));
        if (config != null && !config.permissions().isEmpty()) {
            rules.addAll(config.permissions());
        }
        rules = withSkillRules(name, rules);
        int maxSteps = config == null ? 0 : config.maxSteps();
        Option<String> system = config == null ? Option.none() : Option.of(config.system());
        String mode = config == null ? "all" : config.mode();
        // Subagents always run in deny mode (no questions)
        de.augmentia.oc.engine.core.contract.permission.AskMode askMode =
                "subagent".equalsIgnoreCase(mode)
                        ? de.augmentia.oc.engine.core.contract.permission.AskMode.DENY
                        : null; // inherit global
        if (askMode != null && permissionEngine != null) {
            permissionEngine.registerAgentAskMode(id, askMode);
        }
        return AgentProfile.forAgent(id, system, maxSteps, rules, name, askMode);
    }

    /**
     * Adds the per-agent skill allow/deny list as permission rules. The rules
     * only match the {@code skill} action, so tool permissions are untouched.
     *
     * <p>The order is intentional: a later matching rule wins, so an allow
     * list is expressed as {@code deny *} followed by the allowed patterns.</p>
     */
    private List<Rule> withSkillRules(String agentName, List<Rule> base) {
        if (agentName == null) return base;
        EngineConfig.AgentSkillsConfig skills = agentCatalogue == null
                ? (config == null ? null : config.agentSkills(agentName))
                : agentCatalogue.agentSkills(agentName);
        if (skills == null || !skills.enabled()) return base;
        List<Rule> rules = new ArrayList<>(base);
        if (!skills.allow().isEmpty()) {
            rules.add(new Rule("skill", "*", Effect.DENY));
            for (String pattern : skills.allow()) {
                rules.add(new Rule("skill", pattern, Effect.ALLOW));
            }
        }
        for (String pattern : skills.deny()) {
            rules.add(new Rule("skill", pattern, Effect.DENY));
        }
        return List.copyOf(rules);
    }

    /**
     * Builds the per-agent skill selection for the prompt. Returns
     * {@code null} when the agent has no {@code skills} block, which keeps the
     * previous behaviour.
     */
    private SkillSelection skillSelection(String agentName, String query) {
        if (agentName == null) return null;
        EngineConfig.AgentSkillsConfig skills = agentCatalogue == null
                ? (config == null ? null : config.agentSkills(agentName))
                : agentCatalogue.agentSkills(agentName);
        if (skills == null || !skills.enabled()) return null;
        int max = skills.max();
        return switch (skills.prompt()) {
            case "none" -> SkillSelection.none();
            case "core" -> SkillSelection.core(skills.core(), max);
            case "all" -> SkillSelection.all(max);
            default -> SkillSelection.topK(query,
                    skills.filter() != null ? skills.filter() : config.skillFilter(), max);
        };
    }

    public SubagentRunner.SubagentResult runSubagent(SessionId parent, String agentName, String prompt, int depth) {
        AgentId agentId = agentCatalogue.resolve(agentName);
        if (agentId == null) {
            throw new IllegalArgumentException("unknown subagent: " + agentName);
        }
        if (!agentCatalogue.isSubagent(agentName)) {
            throw new IllegalArgumentException("agent cannot run as subagent: " + agentName);
        }
        List<String> parentRoles = sessionRoles.getOrDefault(parent, List.of());
        if (!agentAllowed(agentName, parentRoles)) {
            throw new IllegalArgumentException("agent not allowed: " + agentName);
        }
        // Recursion protection
        int parentDepth = subagentDepths.getOrDefault(parent, 0);
        if (parentDepth >= 2) {
            throw new IllegalStateException("subagent depth limit reached (max 2)");
        }

        // Resource contention: acquire permit (timeout 5 min)
        try {
            if (!subagentSemaphore.tryAcquire(5, java.util.concurrent.TimeUnit.MINUTES)) {
                throw new IllegalStateException("subagent concurrency limit reached");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for subagent slot");
        }

        // Track this run as a background task so the UI can show it immediately
        // (sync and async subagents share the same task registry).
        String taskId = null;
        BackgroundTasks tasks = (subagentTool == null) ? null : subagentTool.backgroundTasks();
        if (tasks != null) {
            taskId = tasks.create(parent, agentName);
            tasks.attach(taskId, Thread.currentThread());
        }

        try {
            Path directory = Path.of(config.workspace()).toAbsolutePath().normalize();
            Location location = new Location(directory, ProjectId.create(), Option.none());
            AgentProfile agent = agentProfile(agentId, agentName, agentCatalogue.config(agentName));

            SessionId child = sessionService.create(location, agentId, Option.none(), clock.now());
            sessions.put(new SessionInfo(child, location.projectId(), location, "Subagent: " + agentName, agentId,
                    Option.none(), List.of(), Money.zero(), TokenCount.zero(), clock.now(), clock.now(),
                    Option.none(), Option.none(), 0, Option.none(),
                    sessions.info(parent).map(SessionInfo::ownerId).orElse("")));

            subagentDepths.put(child, parentDepth + 1);
            sessionRoles.put(child, parentRoles);
            subagentRegistry.registerChild(parent, child);

            // Notify UI that the subagent started (with child session id).
            if (taskId != null) {
                try {
                    eventBus.publishLive(parent.value(), "{\"_type\":\"SubagentStarted\",\"id\":\"" + taskId
                            + "\",\"agent\":\"" + agentName + "\",\"childSession\":\"" + child.value() + "\"}");
                } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
            }

            PromptResult result = null;
            try {
                String subagentPrompt = "You are a subagent. Do not ask the user questions; "
                        + "make reasonable assumptions and report your result back.\n\n" + prompt;
                result = runPrompt(child, subagentPrompt, agent);

                // Token accumulation: add subagent usage to parent session
                SessionInfo parentInfo = sessions.info(parent).orElse(null);
                if (parentInfo != null) {
                    sessions.put(new SessionInfo(parentInfo.id(), parentInfo.projectId(), parentInfo.location(),
                            parentInfo.title(), parentInfo.agent(), parentInfo.model(), parentInfo.permissions(),
                            parentInfo.cost(),
                            TokenCount.of(parentInfo.tokens().input() + result.inputTokens(),
                                    parentInfo.tokens().output() + result.outputTokens()),
                            parentInfo.created(), clock.now(),
                            parentInfo.idle(), parentInfo.suspended(), parentInfo.resumeAttempts(), parentInfo.archived(), parentInfo.ownerId()));
                }

                String text = result.text() == null || result.text().isBlank()
                        ? "Subagent completed with no text output."
                        : result.text();
                SubagentRunner.SubagentResult out = new SubagentRunner.SubagentResult(text, result.inputTokens() > 0
                        ? TokenCount.of(result.inputTokens(), result.outputTokens())
                        : TokenCount.zero(), child);

                if (tasks != null && taskId != null) {
                    tasks.succeed(taskId, out);
                    try {
                        eventBus.publishLive(parent.value(), "{\"_type\":\"SubagentFinished\",\"id\":\"" + taskId
                                + "\",\"state\":\"succeeded\",\"text\":" + Json.encode(new JsonValue.Str(text)) + "}");
                    } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                }
                return out;
            } catch (Throwable t) {
                if (tasks != null && taskId != null) {
                    boolean cancelled = (t instanceof InterruptedException) || Thread.currentThread().isInterrupted();
                    if (cancelled) {
                        tasks.cancel(parent, taskId);
                        try {
                            eventBus.publishLive(parent.value(), "{\"_type\":\"SubagentCancelled\",\"id\":\"" + taskId + "\"}");
                        } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                    } else {
                        tasks.fail(taskId, t);
                        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                        try {
                            eventBus.publishLive(parent.value(), "{\"_type\":\"SubagentFinished\",\"id\":\"" + taskId
                                    + "\",\"state\":\"failed\",\"text\":" + Json.encode(new JsonValue.Str(msg)) + "}");
                        } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
                    }
                }
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error e) throw e;
                throw new RuntimeException(t);
            }
        } finally {
            subagentSemaphore.release();
        }
    }

    /** Returns the OpenTelemetry tracer, or a no-op tracer when OTEL is disabled. */
    private Tracer tracer() {
        if (tracerInstance != null && tracerInstance.isResolvable()) {
            return tracerInstance.get();
        }
        return io.opentelemetry.api.trace.TracerProvider.noop().get("qoc");
    }

    public List<StoredEvent> events(SessionId id, long after) {
        return journal.history(id.value(), Seq.of(Math.max(0, after)));
    }

    public List<Message> messages(SessionId id) {
        return conversation.messages(id, Seq.zero());
    }

    public boolean interrupt(SessionId id) {
        return interruptAll(id, new java.util.HashSet<>()) > 0;
    }

    private int interruptAll(SessionId id, java.util.Set<SessionId> visited) {
        if (id == null || !visited.add(id)) return 0;
        int count = 0;
        Thread thread = running.get(id);
        if (thread != null) {
            thread.interrupt();
            count++;
        }
        // Cancel background tasks owned by this session.
        if (subagentTool != null) {
            try {
                for (BackgroundTasks.Snapshot t : subagentTool.backgroundTasks().list(id)) {
                    if (t.running()) {
                        subagentTool.backgroundTasks().cancel(id, t.id());
                        count++;
                    }
                }
            } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
        }
        // Cascade to direct child sessions (tracked in the runtime registry).
        for (SessionId child : subagentRegistry.children(id)) {
            count += interruptAll(child, visited);
        }
        return count;
    }

    public boolean isRunning(SessionId id) {
        if (id == null) return false;
        if (running.containsKey(id)) return true;
        for (SessionId c : subagentRegistry.children(id)) {
            if (isRunning(c)) return true;
        }
        if (subagentTool != null) {
            try {
                for (BackgroundTasks.Snapshot t : subagentTool.backgroundTasks().list(id)) {
                    if (t.running()) return true;
                }
            } catch (RuntimeException e) { LOG.debugf("ignored: %s", e.getMessage()); }
        }
        return false;
    }

    public synchronized void reloadConfig() {
        config.reload();
        agentCatalogue.reload();
        Path workspacePath = Path.of(config.workspace()).toAbsolutePath().normalize();
        List<Path> skillRoots = new ArrayList<>();
        skillRoots.add(workspacePath.resolve("skill"));
        skillRoots.add(workspacePath.resolve("skills"));
        skillRoots.add(workspacePath.resolve(".opencode").resolve("skills"));
        for (String configured : config.skillPaths()) {
            Path path = Path.of(configured);
            skillRoots.add(path.isAbsolute() ? path : workspacePath.resolve(path));
        }
        SkillCatalog newSkillCatalog = config.skillsEnabled()
                ? SkillCatalog.of(new SkillScanner(fileSystem).loadRoots(skillRoots, new SkillParser()),
                        config.skillAllow(), config.skillDeny(), config.skillMax())
                : SkillCatalog.empty();
        skillCatalog = newSkillCatalog;
        java.util.function.Function<AgentId, List<Rule>> skillRules = agentId -> {
            String agentName = agentCatalogue.nameOf(agentId);
            EngineConfig.AgentConfig cfg = agentName == null ? null : agentCatalogue.config(agentName);
            return agentProfile(agentId, agentName, cfg).permissions();
        };
        registry.register(SkillTool.definition(skillCatalog, skillRules));
        registry.register(SkillTool.listDefinition(skillCatalog, skillRules));
        allowAll = List.of(new Rule("*", "*", Effect.ALLOW));
        toolSnapshot = registry.snapshot(allowAll);
        toolRuntime = new ToolRuntime(toolSnapshot, effectiveAuthorizer,
                permissionGate, toolHooks, toolOutput, toolResultCache, stepTimeoutSeconds);
        contextBuilder = new ContextBuilder(conversation, skillCatalog, instructionLoader,
                instructions == null ? Option.none() : Option.some(instructions), config.skillFilter(),
                ContextBuilder.leastPrivilegeDefaults());
        LOG.infof("reloaded config: %d agent(s), %d skill(s)",
                agentCatalogue.list().size(), skillCatalog.all().size());
    }

    public synchronized void reloadWorkspace(String newWorkspace, String user, boolean isAdmin) {
        if (newWorkspace == null || newWorkspace.isBlank()) {
            throw new IllegalArgumentException("workspace must not be blank");
        }
        java.nio.file.Path normalized = java.nio.file.Path.of(newWorkspace).toAbsolutePath().normalize();
        try {
            normalized = normalized.toRealPath();
        } catch (java.io.IOException ignored) {
            // Allow a not-yet-existing path to be validated below.
        }
        // Determine the allowed base based on user role.
        // Admin: can switch to /data/workspace (all user workspaces).
        // Regular user: can only switch within their own workspace.
        java.nio.file.Path allowedBase = workspaceBase;
        if (user != null && !user.isBlank() && !"opencode".equalsIgnoreCase(user)
                && !"anonymous".equalsIgnoreCase(user) && !"local".equalsIgnoreCase(user)
                && !"admin".equalsIgnoreCase(user)) {
            if (!isAdmin) {
                // Regular user: base is their own workspace
                java.nio.file.Path userBase = workspaceBase.resolve("user-ws").resolve(user).normalize();
                if (userBase.startsWith(workspaceBase)) {
                    allowedBase = userBase;
                }
            } else {
                // Admin: base is workspaceBase itself, so they can see all user workspaces
                if (workspaceBase != null) {
                    allowedBase = workspaceBase;
                }
            }
        }
        // A project folder may only be switched to a subdirectory of the
        // allowed base workspace.
        if (allowedBase != null && !de.augmentia.oc.engine.runtime.tool.fs.FileAccess.isUnder(allowedBase, normalized)) {
            throw new IllegalArgumentException("workspace must be inside " + allowedBase + ": " + normalized);
        }
        if (!java.nio.file.Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("workspace directory does not exist: " + normalized);
        }
        if (!java.nio.file.Files.isReadable(normalized) || !java.nio.file.Files.isWritable(normalized)) {
            throw new IllegalArgumentException("workspace directory must be readable and writable: " + normalized);
        }
        config.setWorkspace(normalized.toString());
        workspacePath = normalized;
        buildWorkspaceRuntime();
        // Reload agents/skills/settings from the new workspace so that
        // project-level definitions (when workspace settings are enabled)
        // take effect instead of keeping the previous workspace's agents.
        reloadConfig();
        LOG.infof("workspace reloaded: %s", normalized);
    }


    public synchronized void reloadWorkspace(String newWorkspace) {
        reloadWorkspace(newWorkspace, null, false);
    }

    public void createAgent(String name, String content) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("agent name must not be blank");
        if (content == null) content = "";
        String safe = name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) throw new IllegalArgumentException("invalid agent name");
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home", "."),
                    ".config", "opencode", "agents");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve(safe + ".md");
            java.nio.file.Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            reloadConfig();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot create agent: " + name, e);
        }
    }

    public void deleteAgent(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("agent name must not be blank");
        String safe = name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) throw new IllegalArgumentException("invalid agent name");
        if (safe.equalsIgnoreCase("build") || safe.equalsIgnoreCase("plan")) {
            throw new IllegalArgumentException("cannot delete baseline agent: " + safe);
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home", "."),
                    ".config", "opencode", "agents");
            java.nio.file.Path file = dir.resolve(safe + ".md");
            java.nio.file.Files.deleteIfExists(file);
            reloadConfig();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot delete agent: " + name, e);
        }
    }

    /**
     * Fetches a single agent definition from the configured source server.
     * Used by serverless instances to run an agent for exactly one MCP call;
     * the caller materialises it and removes it again afterwards.
     *
     * @return the agent definition in {@code settings.json} shape, or
     *         {@code null} when no source is configured or the agent cannot
     *         be fetched
     */
    public java.util.Map<String, Object> fetchAgentConfig(String name, String callerToken) {
        if (name == null || name.isBlank()) return null;
        String sourceUrl = config.agentSourceUrl();
        if (sourceUrl == null || sourceUrl.isBlank()) return null;
        String baseUrl = sourceUrl.endsWith("/") ? sourceUrl.substring(0, sourceUrl.length() - 1) : sourceUrl;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpRequest.Builder req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/api/agent/"
                            + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET();
            String token = callerToken != null && !callerToken.isBlank()
                    ? callerToken : config.agentSourceToken();
            if (token != null && !token.isBlank()) {
                req.header("Authorization", "Bearer " + token);
            }
            java.net.http.HttpResponse<String> response = client.send(req.build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warnf("agent fetch: server returned %d for %s", response.statusCode(), name);
                return null;
            }
            de.augmentia.oc.engine.core.json.JsonValue parsed =
                    de.augmentia.oc.engine.core.json.Json.parse(response.body()).valueOrNull();
            if (!(parsed instanceof de.augmentia.oc.engine.core.json.JsonValue.Obj obj)) return null;
            de.augmentia.oc.engine.core.json.JsonValue configVal = obj.values().get("config");
            if (!(configVal instanceof de.augmentia.oc.engine.core.json.JsonValue.Obj configObj)) return null;
            Object javaConfig = de.augmentia.oc.engine.core.json.JsonValues.toJava(configObj);
            if (!(javaConfig instanceof java.util.Map<?, ?> map)) return null;
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        } catch (Exception e) {
            LOG.warnf("agent fetch failed for %s: %s", name, e.getMessage());
            return null;
        }
    }

    /** Returns the full agent definition in {@code settings.json} shape. */
    public de.augmentia.oc.engine.core.json.JsonValue.Obj agentConfig(String name) {
        if (name == null || name.isBlank()) return null;
        EngineConfig.AgentConfig cfg = agentCatalogue.config(name);
        if (cfg == null) return null;
        return EngineConfig.agentToJson(cfg, agentCatalogue.agentSkills(name));
    }

    /**
     * Materialises an agent fetched from the server for one MCP call. The
     * caller must call {@link #removeTransientAgent(String)} afterwards.
     */
    public void defineTransientAgent(String name, java.util.Map<String, Object> config) {
        de.augmentia.oc.engine.core.json.JsonValue value =
                de.augmentia.oc.engine.core.json.JsonValues.fromJava(config);
        if (!(value instanceof de.augmentia.oc.engine.core.json.JsonValue.Obj obj)) {
            throw new IllegalArgumentException("invalid agent config");
        }
        EngineConfig.AgentConfig cfg = EngineConfig.agentFromJson(obj);
        EngineConfig.AgentSkillsConfig skills = EngineConfig.agentSkillsFromJson(obj);
        defineTransientAgent(name, cfg, skills);
    }

    /** Materialises an in-memory agent for one MCP call (no filesystem access). */
    public void defineTransientAgent(String name, EngineConfig.AgentConfig cfg,
                                     EngineConfig.AgentSkillsConfig skills) {
        agentCatalogue.define(name, cfg, skills);
    }

    /** Removes an agent that was materialised for a single MCP call. */
    public void removeTransientAgent(String name) {
        agentCatalogue.remove(name);
    }

    /** Returns true when the agent is already part of the local catalogue. */
    public boolean hasAgent(String name) {
        return name != null && !name.isBlank() && agentCatalogue.config(name) != null;
    }

    /**
     * Serverless mode flag. Exposed as a method so callers never touch the
     * {@link EngineConfig} field through the CDI client proxy.
     */
    public boolean isServerlessMode() {
        return config != null && config.isServerlessMode();
    }

    /** Returns the immutable base workspace directory set at startup. */
    public java.nio.file.Path workspaceBase() {
        return workspaceBase;
    }

    /**
     * Registry role flag. A registry QOC instance only serves the agent
     * catalogue/authoring API and must not execute prompts.
     */
    public boolean isRegistryMode() {
        return config != null && config.isRegistryMode();
    }

    /** Whether agent authoring (create/update/delete) is allowed. */
    public boolean isAgentAuthoringEnabled() {
        return config == null || config.agentAuthoringEnabled();
    }

    public List<java.util.Map<String, Object>> availableTools() {
        if (toolSnapshot == null) return List.of();
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        for (de.augmentia.oc.engine.core.contract.tool.ToolDefinition def : toolSnapshot.list()) {
            result.add(Map.of("name", def.effectiveName(),
                    "namespace", def.options().namespace().orElse("")));
        }
        return result;
    }

    public List<String> a2aToolNames() {
        if (a2aConfig == null) return List.of();
        List<String> result = new ArrayList<>();
        for (de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.A2AEndpoint ep : a2aConfig.endpoints()) {
            result.add(de.augmentia.oc.engine.runtime.tool.a2a.A2AToolProvider.toolName(ep, null));
        }
        return result;
    }


    public List<AgentCatalogue.AgentDto> agents() {
        return agentCatalogue.list();
    }

    /**
     * Returns true when the given principal roles may use the agent selected by
     * name or id. Unknown selectors return true so the normal 404/fallback
     * handling remains in charge.
     */
    public boolean agentAllowed(String agentSelector, List<String> principalRoles) {
        if (agentSelector == null || agentSelector.isBlank()) return true;
        AgentCatalogue.AgentDto found = agentCatalogue.list().stream()
                .filter(agent -> agent.name().equalsIgnoreCase(agentSelector)
                        || agent.id().equalsIgnoreCase(agentSelector))
                .findFirst()
                .orElse(null);
        return found == null || agentCatalogue.isAllowed(found.name(), principalRoles);
    }

    public String agentContent(String name) {
        if (name == null || name.isBlank()) return null;
        String safe = name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) return null;
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home", "."),
                    ".config", "opencode", "agents");
            java.nio.file.Path file = dir.resolve(safe + ".md");
            if (!java.nio.file.Files.isRegularFile(file)) return null;
            return java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    public String agentHash(String name) {
        String content = agentContent(name);
        if (content == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    public String agentUpdatedAt(String name) {
        String safe = name == null ? null : name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe == null || safe.isBlank()) return null;
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home", "."),
                    ".config", "opencode", "agents");
            java.nio.file.Path file = dir.resolve(safe + ".md");
            if (!java.nio.file.Files.isRegularFile(file)) return null;
            return java.time.Instant.ofEpochMilli(java.nio.file.Files.getLastModifiedTime(file).toMillis()).toString();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Listed skills (description set, autoinvoke enabled), sorted by ID. */
    public List<SkillDto> skills() {
        return skills(false);
    }

    /** @param includeHidden include skills hidden from the prompt */
    public List<SkillDto> skills(boolean includeHidden) {
        List<de.augmentia.oc.engine.runtime.skill.Skill> skills =
                includeHidden ? skillCatalog.all() : skillCatalog.listed();
        return skills.stream()
                .map(skill -> new SkillDto(skill.id().value(), skill.name(),
                        skill.description(), skill.listed()))
                .toList();
    }

    public record SkillDto(String id, String name, String description, boolean listed) {}

    public String agentName(AgentId id) {
        return agentCatalogue.nameOf(id);
    }

    public List<String> mcpServerNames() {
        return mcp.serverNames();
    }

    public String mcpStatus(String name) {
        return mcp.status(name);
    }

    public String mcpError(String name) {
        return mcp.error(name);
    }

    public String mcpAuthorizationUrl(String name, String clientId, String redirectUri) {
        String effectiveClient = clientId == null || clientId.isBlank() ? "opencode" : clientId;
        String effectiveRedirect = redirectUri == null || redirectUri.isBlank()
                ? "http://127.0.0.1:8080/api/mcp/" + name + "/callback"
                : redirectUri;
        return mcp.manager().client(name) instanceof de.augmentia.oc.engine.runtime.tool.mcp.McpDiscoveryClient discovery
                ? discovery.authorizationUrl(effectiveClient, effectiveRedirect, "state-" + System.currentTimeMillis())
                : null;
    }

    public void mcpAuthenticate(String name, String code, String redirectUri, String clientId, String clientSecret) {
        String effectiveClient = clientId == null || clientId.isBlank() ? "opencode" : clientId;
        String effectiveRedirect = redirectUri == null || redirectUri.isBlank()
                ? "http://127.0.0.1:8080/api/mcp/" + name + "/callback"
                : redirectUri;
        mcp.authenticate(name, code, effectiveRedirect, effectiveClient,
                clientSecret == null ? "" : clientSecret);
    }

    public ConfigDto config() {
        return new ConfigDto(config.provider(), config.model(), config.workspace());
    }

    public List<EngineConfig.ConfigEntry> configEntries() {
        return config.entries();
    }

    public ModelDto model() {
        return new ModelDto(config.provider(), config.model(), 128_000, 8_192);
    }

    public LocationDto location() {
        SessionId first = listSessions().stream().map(SessionInfo::id).findFirst().orElse(null);
        Path directory = Path.of(config.workspace()).toAbsolutePath().normalize();
        String project = first == null ? ProjectId.create().value()
                : sessions.info(first).map(info -> info.projectId().value()).orElse(ProjectId.create().value());
        return new LocationDto(directory.toString(),
                new ProjectDto(project, directory.toString(), directory.toString()));
    }

    public List<SessionInfo> listSessions() {
        return listSessions(null, true);
    }

    /** Lists sessions visible to the given user (admins see all). */
    public List<SessionInfo> listSessions(String user, boolean isAdmin) {
        return sessions.list().stream()
                .filter(info -> isAdmin || isOwner(info, user))
                .sorted(java.util.Comparator.comparing(SessionInfo::updated).reversed())
                .limit(25)
                .toList();
    }

    /**
     * Per-user ownership check. New sessions carry an explicit owner id; legacy
     * sessions fall back to the workspace directory layout.
     * "anonymous", "opencode" and "local" share the installation base directory.
     */
    public boolean isOwner(SessionInfo info, String user) {
        if (info == null || user == null || user.isBlank()) {
            return false;
        }
        String normalizedUser = user.trim();
        if (info.ownerId() != null && !info.ownerId().isBlank()) {
            return info.ownerId().equals(normalizedUser);
        }
        java.nio.file.Path base = (workspaceBase != null ? workspaceBase : java.nio.file.Path.of(config.workspace())).toAbsolutePath().normalize();
        java.nio.file.Path userBase = base;
        if (!"opencode".equalsIgnoreCase(normalizedUser)
                && !"anonymous".equalsIgnoreCase(normalizedUser)
                && !"local".equalsIgnoreCase(normalizedUser)) {
            java.nio.file.Path candidate = base.resolve("user-ws").resolve(normalizedUser).normalize();
            if (candidate.startsWith(base)) {
                userBase = candidate;
            }
        }
        java.nio.file.Path sessionDir = info.location().directory().toAbsolutePath().normalize();
        return sessionDir.startsWith(userBase);
    }

    public record LocationDto(String directory, ProjectDto project) {}

    public record ProjectDto(String id, String directory, String canonical) {}


    private void publishNew(SessionId id, long before) {
        journal.history(id.value(), Seq.of(Math.max(0, before))).forEach(eventBus::publish);
    }

    private Path allowedBaseFor(String user, boolean isAdmin) {
        Path root = workspaceRoot();
        Path allowedBase = root;
        if (user != null && !user.isBlank() && !"opencode".equalsIgnoreCase(user)
                && !"anonymous".equalsIgnoreCase(user) && !"local".equalsIgnoreCase(user)
                && !"admin".equalsIgnoreCase(user)) {
            if (!isAdmin) {
                Path userBase = workspaceBase.resolve("user-ws").resolve(user).normalize();
                if (userBase.startsWith(workspaceBase)) {
                    allowedBase = userBase;
                }
            } else {
                allowedBase = workspaceBase;
            }
        }
        return allowedBase;
    }

    public List<FileEntry> listFiles(String path) {
        return listFiles(path, null, false);
    }

    public List<FileEntry> listFiles(String path, String user, boolean isAdmin) {
        String target = (path == null || path.isBlank()) ? "." : path;
        Path root = workspaceRoot();
        Path allowedBase = allowedBaseFor(user, isAdmin);
        // If current workspace is outside user's allowed base, use allowed base
        if (!root.startsWith(allowedBase) && !root.equals(allowedBase)) {
            root = allowedBase;
        }
        Path resolved = root.resolve(target).normalize();
        if (!resolved.startsWith(root) || !resolved.startsWith(allowedBase)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        resolved = fileSystem.realPath(resolved);
        List<FileEntry> entries = new ArrayList<>();
        for (Path child : fileSystem.list(resolved)) {
            try {
                FileSystem.FileStat stat = fileSystem.stat(child);
                entries.add(new FileEntry(child.getFileName().toString(), child.toString(),
                        root.relativize(child).toString().replace('\\', '/'),
                        stat.directory(), stat.size()));
            } catch (Exception e) { LOG.debugf("ignored: %s", e.getMessage()); }
        }
        return entries;
    }

    public String readFile(String path) {
        return readFile(path, null, false);
    }

    public String readFile(String path, String user, boolean isAdmin) {
        Path root = workspaceRoot();
        Path allowedBase = allowedBaseFor(user, isAdmin);
        if (!root.startsWith(allowedBase) && !root.equals(allowedBase)) {
            root = allowedBase;
        }
        Path resolved = root.resolve(path == null ? "" : path).normalize();
        if (!resolved.startsWith(root) || !resolved.startsWith(allowedBase)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        resolved = fileSystem.realPath(resolved);
        if (java.nio.file.Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("path is a directory: " + path);
        }
        return fileSystem.readText(resolved);
    }

    public byte[] readFileBytes(String path) {
        return readFileBytes(path, null, false);
    }

    public byte[] readFileBytes(String path, String user, boolean isAdmin) {
        Path root = workspaceRoot();
        Path allowedBase = allowedBaseFor(user, isAdmin);
        if (!root.startsWith(allowedBase) && !root.equals(allowedBase)) {
            root = allowedBase;
        }
        Path resolved = root.resolve(path == null ? "" : path).normalize();
        if (!resolved.startsWith(root) || !resolved.startsWith(allowedBase)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        resolved = fileSystem.realPath(resolved);
        if (java.nio.file.Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("path is a directory: " + path);
        }
        return fileSystem.read(resolved);
    }

    public void writeFile(String path, byte[] content) {
        writeFile(path, content, null, false);
    }

    public void writeFile(String path, byte[] content, String user, boolean isAdmin) {
        Path root = workspaceRoot();
        Path allowedBase = allowedBaseFor(user, isAdmin);
        if (!root.startsWith(allowedBase) && !root.equals(allowedBase)) {
            root = allowedBase;
        }
        Path resolved = root.resolve(path == null ? "" : path).normalize();
        if (!resolved.startsWith(root) || !resolved.startsWith(allowedBase)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        fileSystem.write(resolved, content == null ? new byte[0] : content);
    }

    public void createFile(String path, String content) {
        createFile(path, content, null, false);
    }

    public void createFile(String path, String content, String user, boolean isAdmin) {
        writeFile(path, (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8), user, isAdmin);
    }

    public void deletePath(String path) {
        deletePath(path, null, false);
    }

    public void deletePath(String path, String user, boolean isAdmin) {
        Path root = workspaceRoot();
        Path allowedBase = allowedBaseFor(user, isAdmin);
        if (!root.startsWith(allowedBase) && !root.equals(allowedBase)) {
            root = allowedBase;
        }
        Path resolved = root.resolve(path == null ? "" : path).normalize();
        if (!resolved.startsWith(root) || !resolved.startsWith(allowedBase)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        if (resolved.equals(allowedBase) || resolved.equals(root)) {
            throw new IllegalArgumentException("cannot delete the workspace root");
        }
        if (!java.nio.file.Files.exists(resolved)) {
            throw new IllegalArgumentException("path does not exist: " + path);
        }
        try {
            java.nio.file.Files.walkFileTree(resolved, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    java.nio.file.Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir,
                        java.io.IOException exc) throws java.io.IOException {
                    if (exc != null) throw exc;
                    java.nio.file.Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot delete: " + path, e);
        }
    }

    public byte[] zipDirectory(String path) {
        Path dir = fileSystem.realPath(resolvePath(path));
        if (!java.nio.file.Files.isDirectory(dir)) {
            throw new IllegalArgumentException("path is not a directory: " + path);
        }
        String prefix = dir.getFileName() == null ? "download" : dir.getFileName().toString();
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
             java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(dir)) {
                for (Path entry : walk.sorted().toList()) {
                    if (entry.equals(dir)) continue;
                    String name = prefix + "/" + dir.relativize(entry).toString().replace('\\', '/');
                    if (java.nio.file.Files.isDirectory(entry)) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(name + "/"));
                        zip.closeEntry();
                    } else {
                        zip.putNextEntry(new java.util.zip.ZipEntry(name));
                        java.nio.file.Files.copy(entry, zip);
                        zip.closeEntry();
                    }
                }
            }
            zip.finish();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot zip: " + path, e);
        }
    }

    private Path workspaceRoot() {
        return Path.of(config.workspace()).toAbsolutePath().normalize();
    }

    private Path resolvePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path must not be blank");
        Path root = workspaceRoot();
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        return resolved;
    }

    public Map<String, Object> health() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("status", "ok");
        m.put("role", config.role());
        m.put("mode", config.mode());
        m.put("provider", config.provider());
        m.put("model", config.model());
        m.put("workspace", config.workspace());
        return m;
    }

    public String encode(StoredEvent event) {
        return Json.encode(JsonValue.of(Map.of(
                "seq", JsonValue.of(event.seq().value()),
                "type", JsonValue.of(event.type()),
                "version", JsonValue.of(event.version()),
                "data", codec.encode(event.payload()),
                "created", JsonValue.of(event.time().toString()))));
    }

    private ModelRef preparedModel(SessionInfo info, ModelInfo model) {
        return info.model().orElse(new ModelRef(model.providerId(), model.id()));
    }

    private ModelInfo modelInfo() {
        return new ModelInfo(modelRef.model(), modelRef.provider(), List.of(),
                new ModelInfo.ModelLimits(modelContextLimit, modelOutputLimit),
                new ModelInfo.CostModel(Money.zero(), Money.zero(), Money.zero(), Money.zero()),
                new ModelInfo.ModelCapabilities(List.of(Modality.TEXT), List.of(Modality.TEXT)),
                Option.none(), Option.none());
    }

    private static String text(List<de.augmentia.oc.engine.core.contract.provider.ModelEvent> events) {
        StringBuilder text = new StringBuilder();
        for (var event : events) {
            if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextEnded ended) {
                text.append(ended.text());
            }
        }
        if (text.isEmpty()) {
            for (var event : events) {
                if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta delta) {
                    text.append(delta.text());
                }
            }
        }
        return text.toString();
    }


    private ProcessRunner createProcessRunner(Path root) {
        if (!shellSandboxEnabled) {
            return new JdkProcessRunner();
        }
        SandboxMode mode = SandboxMode.fromString(shellSandboxMode);
        if (mode == SandboxMode.NONE || mode == SandboxMode.DANGER_FULL_ACCESS) {
            return new JdkProcessRunner();
        }
        java.util.List<Path> extra = new java.util.ArrayList<>();
        if (shellSandboxExtraWritable != null && shellSandboxExtraWritable.isPresent()) {
            String val = shellSandboxExtraWritable.get();
            if (val != null && !val.isBlank()) {
                for (String part : val.split(",")) {
                    String t = part.trim();
                    if (!t.isEmpty()) extra.add(Path.of(t));
                }
            }
        }
        java.util.List<String> allowlist = new java.util.ArrayList<>();
        if (shellSandboxEnvAllowlist != null && !shellSandboxEnvAllowlist.isBlank()) {
            for (String part : shellSandboxEnvAllowlist.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) allowlist.add(t);
            }
        }
        for (String k : java.util.List.of("AGENT", "OPENCODE", "AI_AGENT", "OPENCODE_SESSION_ID")) {
            if (!allowlist.contains(k)) allowlist.add(k);
        }
        int gid = shellSandboxGid >= 0 ? shellSandboxGid : shellSandboxUid;
        return new SandboxedProcessRunner(
                new JdkProcessRunner(), mode, root, extra,
                shellSandboxUid, gid, shellSandboxBwrap, allowlist);
    }

    private ToolSnapshot buildEphemeralBaseSnapshot(Path root) {
        ToolRegistry r = new ToolRegistry();
        for (ToolDefinition d : registry.list()) {
            if (!WORKSPACE_TOOL_NAMES.contains(d.effectiveName())) {
                r.register(d);
            }
        }
        for (ToolDefinition d : workspaceToolDefinitions(root, false)) {
            r.register(d);
        }
        return r.snapshot(allowAll);
    }

    private java.util.List<ToolDefinition> workspaceToolDefinitions(Path root, boolean includeWorktree) {
        java.util.List<ToolDefinition> defs = new java.util.ArrayList<>();
        NioFileSystem nio = new NioFileSystem(root);
        de.augmentia.oc.engine.adapter.fs.CachedFileSystem fs =
                new de.augmentia.oc.engine.adapter.fs.CachedFileSystem(nio);
        FileAccess access = new FileAccess(fs, root, root);
        defs.add(ReadTool.definition(fs, access, permissionGate, toolOutput));
        defs.add(WriteTool.definition(fs, access, permissionGate));
        defs.add(EditTool.definition(fs, access, permissionGate));
        defs.add(PatchTool.definition(fs, access, permissionGate));
        defs.add(GlobTool.definition(fs, access, permissionGate));
        defs.add(GrepTool.definition(fs, access, permissionGate, toolOutput));
        ProcessRunner processRunner = createProcessRunner(root);
        defs.add(ShellTool.definition(processRunner, root, root, null,
                permissionGate, processRegistry));
        if (includeWorktree && WorktreeTool.available(processRunner, root)) {
            WorktreeTool worktrees = new WorktreeTool(processRunner, root);
            defs.add(WorktreeTool.create(worktrees));
            defs.add(WorktreeTool.list(worktrees));
            defs.add(WorktreeTool.remove(worktrees));
        }
        return defs;
    }

    private static java.util.List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) out.add(trimmed);
        }
        return java.util.List.copyOf(out);
    }

    static void deleteDirectoryRecursively(Path root) {
        if (root == null || !java.nio.file.Files.exists(root)) return;
        try {
            java.nio.file.Files.walkFileTree(root,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult visitFile(Path file,
                                java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                            java.nio.file.Files.deleteIfExists(file);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
                            if (exc != null) throw exc;
                            if (!dir.equals(root)) java.nio.file.Files.deleteIfExists(dir);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
            java.nio.file.Files.deleteIfExists(root);
        } catch (java.io.IOException e) {
            throw new RuntimeException("cannot delete directory: " + root, e);
        }
    }

    private ToolSnapshot filterToolSnapshot(ToolSnapshot global, EngineConfig.AgentConfig agentConfig) {
        if (global == null) return ToolSnapshot.empty();
        if (agentConfig == null) return global;
        List<String> allow = agentConfig.toolAllow();
        List<String> deny = new ArrayList<>(agentConfig.toolDeny() == null ? List.of() : agentConfig.toolDeny());
        List<String> mcpServers = agentConfig.mcpServers();

        // An agent with skills disabled must not reach the skill tools, even if
        // its tool allow list contains "*".
        EngineConfig.AgentSkillsConfig agentSkills = agentCatalogue == null ? null : agentCatalogue.agentSkills(agentConfig.name());
        boolean skillsEnabled = agentSkills == null || agentSkills.enabled();
        if (!skillsEnabled) {
            if (!deny.contains("skill")) deny.add("skill");
            if (!deny.contains("skill_list")) deny.add("skill_list");
        }

        java.util.Set<String> allowedNames = new java.util.LinkedHashSet<>();
        for (ToolDefinition def : global.list()) {
            String name = def.effectiveName();
            if (SubmitResult.TOOL_NAME.equals(name)) {
                allowedNames.add(name);
                continue;
            }
            if (!allowed(name, allow)) continue;
            if (denied(name, deny)) continue;
            if (def.options().namespace().isPresent() && !mcpAllowed(name, mcpServers)) continue;
            allowedNames.add(name);
        }

        Map<ToolName, ToolDefinition> filtered = new java.util.LinkedHashMap<>();
        for (ToolDefinition def : global.list()) {
            if (allowedNames.contains(def.effectiveName())) {
                filtered.put(def.name(), def);
            }
        }
        return new ToolSnapshot(filtered);
    }

    private static boolean allowed(String name, List<String> allow) {
        if (allow == null || allow.isEmpty()) return false;
        for (String a : allow) {
            if ("*".equals(a) || a.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static boolean denied(String name, List<String> deny) {
        if (deny == null) return false;
        for (String d : deny) {
            if ("*".equals(d) || d.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static boolean mcpAllowed(String name, List<String> servers) {
        if (servers == null || servers.isEmpty()) return false;
        if (servers.contains("*")) return true;
        int idx = name.indexOf('_');
        if (idx < 0) return false;
        String server = name.substring(0, idx);
        return servers.stream().anyMatch(s -> s.equalsIgnoreCase(server));
    }


    private static Hooks noopHooks() {
        return new Hooks() {
            @Override
            public ToolCall beforeToolCall(ToolContext context, ToolCall call) {
                return call;
            }

            @Override
            public ToolResult afterToolCall(ToolContext context, ToolResult result) {
                return result;
            }
        };
    }

    /**
     * Explicit outcome of a prompt/turn. Clients can distinguish "no turn
     * happened" from a model refusal, a technical failure and a cancellation
     * without parsing a finish string.
     */
    public sealed interface TurnOutcome permits TurnOutcome.Completed, TurnOutcome.Busy,
            TurnOutcome.Refused, TurnOutcome.Failed, TurnOutcome.Terminated, TurnOutcome.Cancelled,
            TurnOutcome.Paused {

        record Completed(String finish, long inputTokens, long outputTokens, String text,
                         Option<JsonValue> structured) implements TurnOutcome {}

        record Busy(String message) implements TurnOutcome {}

        record Refused(String reason, String detail) implements TurnOutcome {}

        record Failed(SessionError error, boolean retryable) implements TurnOutcome {}

        record Terminated(InterruptReason reason) implements TurnOutcome {}

        record Cancelled() implements TurnOutcome {}

        record Paused(String approvalId, String approvalUrl, String message) implements TurnOutcome {
            public Paused {
                if (approvalId == null || approvalId.isBlank()) throw new IllegalArgumentException("approvalId must not be blank");
                if (approvalUrl == null) approvalUrl = "";
                if (message == null) message = "approval pending";
            }
        }
    }

    public record PromptResult(TurnOutcome outcome, String finish, long inputTokens,
                               long outputTokens, String text, Option<JsonValue> structured) {
        public PromptResult {
            if (structured == null) structured = Option.none();
            if (outcome == null) {
                outcome = new TurnOutcome.Completed(finish, inputTokens, outputTokens, text, structured);
            }
        }

        public PromptResult(String finish, long inputTokens, long outputTokens, String text,
                            Option<JsonValue> structured) {
            this(new TurnOutcome.Completed(finish, inputTokens, outputTokens, text,
                    structured == null ? Option.none() : structured),
                    finish, inputTokens, outputTokens, text, structured);
        }

        public PromptResult(String finish, long inputTokens, long outputTokens, String text) {
            this(finish, inputTokens, outputTokens, text, Option.none());
        }

        public static PromptResult busy(String message) {
            return new PromptResult(new TurnOutcome.Busy(message), "BUSY", 0, 0, "",
                    Option.none());
        }

        public static PromptResult failed(SessionError error, boolean retryable) {
            return new PromptResult(new TurnOutcome.Failed(error, retryable), "FAILED", 0, 0, "",
                    Option.none());
        }

        public static PromptResult cancelled() {
            return new PromptResult(new TurnOutcome.Cancelled(), "CANCELLED", 0, 0, "",
                    Option.none());
        }

        public boolean completed() {
            return outcome instanceof TurnOutcome.Completed;
        }
    }

    public record FileEntry(String name, String path, String relative, boolean directory, long size) {}

    public record ConfigDto(String provider, String model, String workspace) {}

    public record ModelDto(String provider, String model, int context, int output) {}
}
