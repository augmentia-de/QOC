package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
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
import de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry;
import de.augmentia.oc.engine.runtime.tool.shell.ProcessTool;
import de.augmentia.oc.engine.runtime.tool.shell.ShellTool;
import de.augmentia.oc.engine.runtime.tool.subagent.BackgroundTasks;
import de.augmentia.oc.engine.runtime.tool.subagent.SubagentRunner;
import de.augmentia.oc.engine.runtime.tool.subagent.SubagentTool;
import de.augmentia.oc.engine.runtime.tool.subagent.TaskTool;
import de.augmentia.oc.engine.runtime.tool.web.FirecrawlTool;
import de.augmentia.oc.engine.runtime.tool.web.WebFetchTool;
import de.augmentia.oc.engine.runtime.tool.fs.WriteTool;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Wiring of the engine for the Quarkus outer skin (M14). */
@ApplicationScoped
public class Engine {

    @Inject
    DataSource dataSource;

    @Inject
    EngineMetrics metrics;

    @Inject
    de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider;

    @Inject
    EventBus eventBus;

    @Inject
    AgentCatalogue agentCatalogue;

    private static final Logger LOG = Logger.getLogger(Engine.class);

    @Inject
    jakarta.enterprise.inject.Instance<Tracer> tracerInstance;

    @Inject
    EngineConfig config;

    @Inject
    @ConfigProperty(name = "engine.db.kind", defaultValue = "postgresql")
    String dbKind;

    @Inject
    @ConfigProperty(name = "engine.firecrawl.api-key")
    java.util.Optional<String> firecrawlApiKey;

    @Inject
    @ConfigProperty(name = "engine.firecrawl.endpoint", defaultValue = FirecrawlTool.DEFAULT_ENDPOINT)
    String firecrawlEndpoint;

    @Inject
    @ConfigProperty(name = "engine.session.retention-days", defaultValue = "14")
    long sessionRetentionDays;

    @Inject
    @ConfigProperty(name = "engine.revert.snapshot.enabled", defaultValue = "true")
    boolean revertSnapshotEnabled;

    @Inject
    @ConfigProperty(name = "engine.instructions.enabled", defaultValue = "true")
    boolean instructionsEnabled;

    @Inject
    WebApprovalSource approvalSource;

    @Inject
    WebQuestionSource questionSource;

    private TodoTool todoTool;

    private Clock clock;
    private Journal journal;
    private ConversationStore conversation;
    private SessionStore sessions;
    private SessionService sessionService;
    private ContextBuilder contextBuilder;
    private CommandLoader commandLoader;
    private SkillCatalog skillCatalog = SkillCatalog.empty();
    private TitleGenerator titleGenerator;
    private Step step;
    private ToolSnapshot toolSnapshot;
    private ToolRegistry registry;
    private InstructionLoader instructionLoader = InstructionLoader.none();
    private PermissionGate permissionGate;
    private ToolOutputStore toolOutput;
    private ToolRuntime toolRuntime;
    private Hooks toolHooks = noopHooks();
    private ModelRef modelRef;
    private FileSystem fileSystem;
    private InstructionStore instructions;
    private CompactionService compaction;
    private McpServerManager mcp;
    private List<Rule> allowAll;
    private SubagentTool subagentTool;
    private ProcessRegistry processRegistry;
    private PermissionEngine permissionEngine;
    private SessionRetention sessionRetention;
    private ScheduledExecutorService retentionExecutor;
    private SnapshotStore snapshots;
    private Revert revert;
    private final java.util.concurrent.ConcurrentHashMap<SessionId, CapturedSnapshot> revertSnapshots =
            new java.util.concurrent.ConcurrentHashMap<>();
    private record CapturedSnapshot(SnapshotId id, List<Path> paths) {}
    private final EventCodec codec = EventCodec.standard();
    private final java.util.concurrent.ConcurrentHashMap<SessionId, Thread> running =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Bounded prompt admission: overload degrades to BUSY instead of OOM. */
    private java.util.concurrent.Semaphore promptSlots;

    @ConfigProperty(name = "engine.max.concurrent.prompts", defaultValue = "16")
    int maxConcurrentPrompts;

    @ConfigProperty(name = "engine.turn.max.tool-calls", defaultValue = "64")
    int maxTurnToolCalls;

    @ConfigProperty(name = "engine.turn.max.wall-minutes", defaultValue = "30")
    long maxTurnWallMinutes;

    @PostConstruct
    void wire() {
        clock = new SystemClock();
        promptSlots = new java.util.concurrent.Semaphore(Math.max(1, maxConcurrentPrompts));
        boolean sqlite = "sqlite".equalsIgnoreCase(dbKind);
        if (sqlite) Sqlite.enableWal(dataSource);
        journal = sqlite ? new SqliteJournal(dataSource, clock) : new SqlJournal(dataSource, clock);
        conversation = sqlite ? new SqliteConversation(dataSource) : new SqlConversation(dataSource);
        sessions = sqlite ? new SqliteSessions(dataSource) : new SqlSessions(dataSource);
        ClaimStore claims = sqlite ? new SqliteClaims(dataSource) : new SqlClaims(dataSource);
        ApprovalStore approvals = sqlite ? new SqliteApprovals(dataSource) : new SqlApprovals(dataSource);
        instructions = sqlite ? new SqliteInstructions(dataSource) : new SqlInstructions(dataSource);
        toolOutput = sqlite
                ? new SqliteToolOutput(dataSource, Path.of("tool-output"))
                : new SqlToolOutput(dataSource, Path.of("tool-output"));
        SessionWake wake = session -> {
            // Background notification: if the session is not currently running,
            // trigger a runPrompt in a background thread to process pending
            // background subagent results. This is a best-effort notification;
            // the parent can also retrieve results via subagent(sessionID=...).
            if (running.containsKey(session)) {
                LOG.debug("SessionWake: session " + session.value() + " is running, skip");
                return;
            }
            new Thread(() -> {
                try {
                    // Check for completed background subagent results
                    // and process them via a synthetic prompt.
                    LOG.infof("SessionWake: processing background results for %s", session.value());
                } catch (Exception e) {
                    LOG.warn("SessionWake failed for " + session.value() + ": " + e.getMessage());
                }
            }, "wake-" + session.value()).start();
        };

        sessionService = new SessionService(journal, sessions, wake, sessions::removeInbox);
        sessionRetention = new SessionRetention(sessions, journal, clock, sessionRetentionDays);
        ApprovalStore permissionApprovals = sqlite ? new SqliteApprovals(dataSource) : new SqlApprovals(dataSource);
        CredentialStore credentialStore = sqlite ? new SqliteCredentials(dataSource) : new SqlCredentials(dataSource);
        permissionEngine = new PermissionEngine(Option.some(permissionApprovals),
                Option.some(approvalSource), Option.some((PolicySource) () -> List.of()), Option.none());
        if (approvalSource != null) {
            int orphaned = approvalSource.recoverOrphans();
            if (orphaned > 0) {
                LOG.warnf("recovered %d orphaned approval request(s)", orphaned);
            }
        }

        Path workspacePath = Path.of(config.workspace()).toAbsolutePath().normalize();
        commandLoader = new CommandLoader(workspacePath,
                Path.of(System.getProperty("user.home", "."), ".config", "opencode"));
        NioFileSystem nio = new NioFileSystem(workspacePath);
        fileSystem = new CachedFileSystem(nio);
        FileSystem fs = fileSystem;
        snapshots = sqlite ? new SqliteSnapshots(dataSource, fs) : new SqlSnapshots(dataSource, fs);
        revert = new Revert(journal, Option.some(snapshots), Option.some(instructions));
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
                    if (cfg != null && !cfg.permissions().isEmpty()) {
                        agentRules = cfg.permissions();
                    }
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
            permissionEngine.assertAllowed(new PermissionCheck(request, agent, agentRules, sessionRules, List.of(), List.of()));
            pspan.setAttribute("result", "checked");
            pspan.end();
            }
        };
        registry = new ToolRegistry();
        registry.register(ReadTool.definition(fs, access, permissionGate, toolOutput));
        registry.register(WriteTool.definition(fs, access, permissionGate));
        registry.register(EditTool.definition(fs, access, permissionGate));
        registry.register(PatchTool.definition(fs, access, permissionGate));
        registry.register(GlobTool.definition(fs, access, permissionGate));
        registry.register(GrepTool.definition(fs, access, permissionGate, toolOutput));
        JdkProcessRunner processRunner = new JdkProcessRunner();
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
        registry.register(QuestionTool.definition(new QuestionTool(questionSource)));
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
        java.util.function.Function<AgentId, List<Rule>> skillRules = agentId ->
                agentProfile(agentId, agentCatalogue.nameOf(agentId),
                        agentCatalogue.config(agentCatalogue.nameOf(agentId))).permissions();
        registry.register(SkillTool.definition(skillCatalog, skillRules));
        registry.register(SkillTool.listDefinition(skillCatalog, skillRules));
        allowAll = List.of(new Rule("*", "*", Effect.ALLOW));
        mcp = new McpServerManager(registry, new JdkHttpTransport(), new JdkProcessRunner(),
                Option.some(credentialStore));
        for (de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig server : config.mcpServers()) {
            mcp.add(server);
        }
        registry.register(McpResourceTools.list(mcp));
        registry.register(McpResourceTools.read(mcp));
        toolSnapshot = registry.snapshot(allowAll);
        toolRuntime = new ToolRuntime(toolSnapshot, ToolAuthorizer.defaultAuthorizer(),
                permissionGate, toolHooks, toolOutput);
        instructionLoader = instructionsEnabled
                ? new NioInstructionLoader(Path.of(System.getProperty("user.home", "."),
                        ".config", "opencode"))
                : InstructionLoader.none();
        contextBuilder = new ContextBuilder(conversation, skillCatalog, instructionLoader,
                instructions == null ? Option.none() : Option.some(instructions));
        modelRef = new ModelRef(ProviderId.create(), ModelId.create());
        titleGenerator = new TitleGenerator(provider, modelRef);
        step = new Step(provider, toolRuntime);
        if (subagentTool != null) {
            subagentTool.setRunner((parent, agentName, prompt, depth) ->
                    runSubagent(parent, agentName, prompt, depth));
        }
        compaction = new CompactionService(journal, conversation, instructions,
                new Compaction(journal, provider), clock, 8, provider);
        startRetention();
    }

    private void startRetention() {
        if (sessionRetentionDays <= 0) return;
        retentionExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qoc-session-retention");
            thread.setDaemon(true);
            return thread;
        });
        retentionExecutor.scheduleWithFixedDelay(this::purgeExpiredSessions, 0, 1, TimeUnit.HOURS);
    }

    private void purgeExpiredSessions() {
        try {
            int deleted = sessionRetention.purgeExpired(id -> running.containsKey(id));
            if (deleted > 0) LOG.infof("session retention deleted %d session(s)", deleted);
        } catch (RuntimeException e) {
            LOG.warn("session retention failed: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void shutdownRetention() {
        if (retentionExecutor != null) retentionExecutor.shutdownNow();
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
        return sessionRetention.delete(id);
    }

    public SessionId createSession() {
        return createSession(null, null);
    }

    public SessionId createSession(String agentName) {
        return createSession(agentName, null);
    }

    public SessionId createSession(String agentName, String user) {
        String normalizedUser = (user == null || user.isBlank()) ? "anonymous" : user.trim();
        Path base = Path.of(config.workspace()).toAbsolutePath().normalize();
        Path directory = base;
        if (!"opencode".equalsIgnoreCase(normalizedUser) && !"anonymous".equalsIgnoreCase(normalizedUser)) {
            Path candidate = base.resolve(normalizedUser).normalize();
            if (candidate.startsWith(base) && java.nio.file.Files.isDirectory(candidate)) {
                directory = candidate;
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
        sessions.put(new SessionInfo(id, location.projectId(), location, "New session", agent,
                Option.none(), List.of(), Money.zero(), TokenCount.zero(), now, now,
                Option.none(), Option.none(), 0, Option.none()));
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
                info.resumeAttempts(), info.archived()));
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
        for (WebApprovalSource.PendingApproval durable : approvalSource.pending(id.value())) {
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
                Money.zero(), TokenCount.zero(), now, now, Option.none(), Option.none(), 0, Option.none()));

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
        return questionSource.pending(id);
    }

    public boolean replyQuestion(SessionId id, String questionId, List<String> selected, String text) {
        return questionSource.resolve(id, questionId, selected, text);
    }

    /** Liveness is process-local; readiness also checks DB and workspace. */
    public boolean ready() {
        try (var connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) return false;
        } catch (java.sql.SQLException e) {
            LOG.warnf("readiness: database check failed: %s", e.getMessage());
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
                Instant.now(), info.idle(), info.suspended(), info.resumeAttempts(), info.archived());
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
                info.resumeAttempts(), info.archived()));
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
        if (resultSchema != null) {
            runSnapshot = SubmitResult.withTool(toolSnapshot, resultSchema);
        }

        InboxId inboxId = InboxId.create();
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
                info.resumeAttempts(), info.archived()));
        info = sessions.info(id).orElseThrow();
        LOG.debugf("runPrompt session=%s agent=%s", id.value(), info.agent());

        Seq seq = conversation.nextSeq(id);
        conversation.insert(id, seq, new Message.UserMessage(MessageId.create(), text,
                List.of(), List.of(), List.of(), Map.of(), clock.now()));

        String agentName = agentCatalogue.nameOf(info.agent());
        EngineConfig.AgentConfig agentConfig = agentCatalogue.config(agentName);
        AgentProfile agent = agentOverride != null ? agentOverride
                : agentProfile(info.agent(), agentName, agentConfig);

        // Model override from agent config
        ModelInfo model = modelInfo();
        if (agentConfig != null && agentConfig.model() != null && !agentConfig.model().isBlank()) {
            model = new ModelInfo(ModelId.of(agentConfig.model()), model.providerId(), model.variants(),
                    model.limits(), model.cost(), model.capabilities(), model.compaction(), model.transport());
        }

        // Agent-specific tool snapshot
        ToolSnapshot agentToolSnapshot = filterToolSnapshot(runSnapshot, agentConfig);

        if (resultSchema != null) {
            PermissionGate structuredGate = request -> {
                if (!SubmitResult.TOOL_NAME.equals(request.action())) {
                    permissionGate.assertAllowed(request);
                }
            };
            runStep = new Step(provider, new ToolRuntime(agentToolSnapshot,
                    ToolAuthorizer.defaultAuthorizer(), structuredGate, toolHooks, toolOutput));
        } else if (agentToolSnapshot != runSnapshot) {
            runStep = new Step(provider, new ToolRuntime(agentToolSnapshot,
                    ToolAuthorizer.defaultAuthorizer(), permissionGate, toolHooks, toolOutput));
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

        int stepNo = 1;
        StepResult.Finished finished = null;
        FinishReason finishReason = FinishReason.NORMAL;
        TokenCount usage = TokenCount.zero();
        String finalText = "";
        Option<JsonValue> structured = Option.none();
        boolean submitForced = false;
        de.augmentia.oc.engine.core.eval.TurnPolicy turnPolicy =
                new de.augmentia.oc.engine.core.eval.TurnPolicy(
                        Math.max(1, agent.maxSteps()) + 1,
                        Math.max(1, maxTurnToolCalls), 200_000,
                        java.time.Duration.ofMinutes(Math.max(1, maxTurnWallMinutes)));
        int totalToolCalls = 0;
        long totalTokens = 0;
        java.time.Instant turnStarted = clock.now();

        while (true) {
            LOG.debugf("step %d session=%s", stepNo, id.value());
            Span stepSpan = tracer().spanBuilder("engine.step")
                    .setAttribute("session.id", id.value())
                    .setAttribute("step", (long) stepNo)
                    .startSpan();
            try (Scope stepScope = stepSpan.makeCurrent()) {
            Option<Choice> forcedChoice = resultSchema != null && submitForced
                    ? Option.some(new Choice.One(new ToolName(SubmitResult.TOOL_NAME)))
                    : Option.none();
            var prepared = contextBuilder.prepare(info, agent, model, agentToolSnapshot, Seq.zero(),
                    stepNo, forcedChoice);
            if (resultSchema != null) {
                prepared = prepared.withSystem(SubmitResult.INSTRUCTION);
            }
            ToolContext context = new ToolContext(id, info.agent(), MessageId.create(), CallId.create());
            StepResult result = runStep.run(prepared, context, liveSink);
            if (result instanceof StepResult.NeedsCompaction) {
                // Automatic compaction (I-07, I-09): exactly one per step.
                Span cspan = tracer().spanBuilder("compaction").setAttribute("session.id", id.value()).startSpan();
                try (Scope cscope = cspan.makeCurrent()) {
                compaction.compact(id, CompactionReason.OVERFLOW, preparedModel(info, model),
                        model.limits().context(), Option.none(),
                        Seq.of(journal.history(id.value(), Seq.zero()).size()),
                        clock.now());
                metrics.compaction();
                cspan.end();
                }
                prepared = contextBuilder.prepare(info, agent, model, agentToolSnapshot, Seq.zero(),
                        stepNo, forcedChoice);
                if (resultSchema != null) {
                    prepared = prepared.withSystem(SubmitResult.INSTRUCTION);
                }
                result = runStep.run(prepared, context, liveSink);
            }
            if (result instanceof StepResult.Failed failed) {
                throw new IllegalStateException("model call failed: " + failed.message());
            }
            if (!(result instanceof StepResult.Finished done)) {
                throw new IllegalStateException("context overflow: compaction required");
            }
            finished = done;
            finishReason = done.reason();
            usage = done.usage();
            totalToolCalls += done.toolCalls().size();
            totalTokens += usage.total();
            metrics.toolCalls(done.toolCalls().size());
            String stepText = text(done.events());
            if (!stepText.isBlank()) finalText = stepText;

            // Persist the assistant turn (text + tool calls/results) so the next step sees it.
            List<Content> assistantContent = new ArrayList<>();
            if (!stepText.isBlank()) {
                assistantContent.add(new Content.TextContent(stepText, clock.now()));
            }
            for (StepResult.ToolCallOutcome outcome : done.toolCalls()) {
                ToolCall call = outcome.call();
                ToolState state;
                if (outcome.outcome() instanceof ToolOutcome.Ok ok) {
                    state = new ToolState.Completed(call.input(), ok.result().content(), ok.result().metadata());
                } else if (outcome.outcome() instanceof ToolOutcome.Failed failed) {
                    state = new ToolState.Failed(call.input(), failed.error(), Option.none(), failed.metadata());
                } else {
                    state = new ToolState.Completed(call.input(), List.of(), Map.of());
                }
                assistantContent.add(new Content.ToolCardContent(
                        new ToolCard(call.id(), call.name(), call.input(), state, true, clock.now())));
            }
            if (!assistantContent.isEmpty()) {
                Seq assistantSeq = conversation.nextSeq(id);
                conversation.insert(id, assistantSeq, new Message.AssistantMessage(
                        MessageId.create(), info.agent(),
                        info.model().orElse(new ModelRef(model.providerId(), model.id())),
                        assistantContent, Option.some(finishReason), Option.none(), Option.none(),
                        Option.none(), Money.zero(), usage, AssistantTime.started(clock.now())));
            }
            LOG.debugf("persisted assistant turn session=%s step=%d tools=%d", id.value(), stepNo, done.toolCalls().size());
            stepSpan.setAttribute("finish", finished.reason().name());
            stepSpan.setAttribute("tool_calls", (long) finished.toolCalls().size());
            for (StepResult.ToolCallOutcome outcome : finished.toolCalls()) {
                stepSpan.addEvent("tool.call", io.opentelemetry.api.common.Attributes.of(
                        io.opentelemetry.api.common.AttributeKey.stringKey("tool.name"), outcome.call().name().value()));
            }
            } finally {
            stepSpan.end();
            }

            if (resultSchema != null) {
                Option<JsonValue> submitted = SubmittedResult.result(finished, resultSchema);
                if (submitted.isPresent()) {
                    structured = submitted;
                    break;
                }
            }
            if (submitForced) break;

            boolean hasToolCalls = !finished.toolCalls().isEmpty();
            if (!hasToolCalls) {
                if (resultSchema != null) {
                    submitForced = true;
                    stepNo++;
                    continue;
                }
                break;
            }
            de.augmentia.oc.engine.core.eval.TurnPolicy.Decision decision =
                    turnPolicy.decide(stepNo, totalToolCalls,
                            TokenCount.of(totalTokens, 0),
                            java.time.Duration.between(turnStarted, clock.now()));
            if (decision != de.augmentia.oc.engine.core.eval.TurnPolicy.Decision.CONTINUE) {
                if (resultSchema != null) {
                    submitForced = true;
                    stepNo++;
                    continue;
                }
                break;
            }
            stepNo++;
        }

        TurnOutcome outcome = switch (finishReason) {
            case CONTENT_FILTER -> new TurnOutcome.Refused("content_filter",
                    "provider stopped the turn because of a content filter");
            case ABORTED -> new TurnOutcome.Cancelled();
            default -> new TurnOutcome.Completed(finishReason.name(), usage.input(),
                    usage.output(), finalText, structured);
        };
        PromptResult promptResult = new PromptResult(outcome, finishReason.name(),
                usage.input(), usage.output(), finalText, structured);

        // Generate title after the first successful prompt if not yet set.
        // Generate title after the first successful prompt if not yet set.
        if (info.title().equals("New session") || info.title().isBlank()) {
            generateTitle(id);
        }
        return promptResult;
    }

    private final java.util.Map<SessionId, Integer> subagentDepths = new java.util.concurrent.ConcurrentHashMap<>();

    private static final int MAX_CONCURRENT_SUBAGENTS = 4;
    private final java.util.concurrent.Semaphore subagentSemaphore = new java.util.concurrent.Semaphore(MAX_CONCURRENT_SUBAGENTS);

    AgentProfile agentProfile(AgentId id, String name, EngineConfig.AgentConfig config) {
        List<Rule> rules = (config == null || config.permissions().isEmpty())
                ? List.of(new Rule("*", "*", Effect.ALLOW), new Rule("grep", "*", Effect.ASK))
                : config.permissions();
        int maxSteps = config == null ? 10 : config.maxSteps();
        Option<String> system = config == null ? Option.none() : Option.of(config.system());
        return AgentProfile.forAgent(id, system, maxSteps, rules, name);
    }

    public SubagentRunner.SubagentResult runSubagent(SessionId parent, String agentName, String prompt, int depth) {
        AgentId agentId = agentCatalogue.resolve(agentName);
        if (agentId == null) {
            throw new IllegalArgumentException("unknown subagent: " + agentName);
        }
        if (agentName.equalsIgnoreCase("build") || agentName.equalsIgnoreCase("plan")) {
            throw new IllegalArgumentException("agent cannot run as subagent: " + agentName);
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

        try {
            Path directory = Path.of(config.workspace()).toAbsolutePath().normalize();
            Location location = new Location(directory, ProjectId.create(), Option.none());
            AgentProfile agent = agentProfile(agentId, agentName, agentCatalogue.config(agentName));

            SessionId child = sessionService.create(location, agentId, Option.none(), clock.now());
            sessions.put(new SessionInfo(child, location.projectId(), location, "Subagent: " + agentName, agentId,
                    Option.none(), List.of(), Money.zero(), TokenCount.zero(), clock.now(), clock.now(),
                    Option.none(), Option.none(), 0, Option.none()));

            subagentDepths.put(child, parentDepth + 1);

            PromptResult result = runPrompt(child, prompt, agent);

            // Token accumulation: add subagent usage to parent session
            SessionInfo parentInfo = sessions.info(parent).orElse(null);
            if (parentInfo != null) {
                sessions.put(new SessionInfo(parentInfo.id(), parentInfo.projectId(), parentInfo.location(),
                        parentInfo.title(), parentInfo.agent(), parentInfo.model(), parentInfo.permissions(),
                        parentInfo.cost(),
                        TokenCount.of(parentInfo.tokens().input() + result.inputTokens(),
                                parentInfo.tokens().output() + result.outputTokens()),
                        parentInfo.created(), clock.now(),
                        parentInfo.idle(), parentInfo.suspended(), parentInfo.resumeAttempts(), parentInfo.archived()));
            }

            String text = result.text() == null || result.text().isBlank()
                    ? "Subagent completed with no text output."
                    : result.text();
            return new SubagentRunner.SubagentResult(text, result.inputTokens() > 0
                    ? TokenCount.of(result.inputTokens(), result.outputTokens())
                    : TokenCount.zero(), child);
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
        Thread thread = running.get(id);
        if (thread == null) return false;
        thread.interrupt();
        return true;
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
        java.util.function.Function<AgentId, List<Rule>> skillRules = agentId ->
                agentProfile(agentId, agentCatalogue.nameOf(agentId),
                        agentCatalogue.config(agentCatalogue.nameOf(agentId))).permissions();
        registry.register(SkillTool.definition(skillCatalog, skillRules));
        registry.register(SkillTool.listDefinition(skillCatalog, skillRules));
        allowAll = List.of(new Rule("*", "*", Effect.ALLOW));
        toolSnapshot = registry.snapshot(allowAll);
        toolRuntime = new ToolRuntime(toolSnapshot, ToolAuthorizer.defaultAuthorizer(),
                permissionGate, toolHooks, toolOutput);
        contextBuilder = new ContextBuilder(conversation, skillCatalog, instructionLoader,
                instructions == null ? Option.none() : Option.some(instructions));
        LOG.infof("reloaded config: %d agent(s), %d skill(s)",
                agentCatalogue.list().size(), skillCatalog.all().size());
    }

    public List<AgentCatalogue.AgentDto> agents() {
        return agentCatalogue.list();
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
        return mcp.status(name).name();
    }

    public String mcpError(String name) {
        return mcp.error(name);
    }

    public String mcpAuthorizationUrl(String name, String clientId, String redirectUri) {
        String effectiveClient = clientId == null || clientId.isBlank() ? "opencode" : clientId;
        String effectiveRedirect = redirectUri == null || redirectUri.isBlank()
                ? "http://127.0.0.1:8080/api/mcp/" + name + "/callback"
                : redirectUri;
        return mcp.client(name) instanceof de.augmentia.oc.engine.runtime.tool.mcp.McpDiscoveryClient discovery
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
        mcp.reload(name);
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
        return sessions.list().stream()
                .sorted(java.util.Comparator.comparing(SessionInfo::updated).reversed())
                .limit(25)
                .toList();
    }

    public record LocationDto(String directory, ProjectDto project) {}

    public record ProjectDto(String id, String directory, String canonical) {}


    private void publishNew(SessionId id, long before) {
        journal.history(id.value(), Seq.of(Math.max(0, before))).forEach(eventBus::publish);
    }

    public List<FileEntry> listFiles(String path) {
        String target = (path == null || path.isBlank()) ? "." : path;
        Path root = workspaceRoot();
        Path resolved = fileSystem.realPath(resolvePath(target));
        List<FileEntry> entries = new ArrayList<>();
        for (Path child : fileSystem.list(resolved)) {
            try {
                FileSystem.FileStat stat = fileSystem.stat(child);
                entries.add(new FileEntry(child.getFileName().toString(), child.toString(),
                        root.relativize(child).toString().replace('\\', '/'),
                        stat.directory(), stat.size()));
            } catch (Exception ignored) { }
        }
        return entries;
    }

    public String readFile(String path) {
        Path resolved = fileSystem.realPath(resolvePath(path));
        if (java.nio.file.Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("path is a directory: " + path);
        }
        return fileSystem.readText(resolved);
    }

    public byte[] readFileBytes(String path) {
        Path resolved = fileSystem.realPath(resolvePath(path));
        if (java.nio.file.Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("path is a directory: " + path);
        }
        return fileSystem.read(resolved);
    }

    public void writeFile(String path, byte[] content) {
        fileSystem.write(resolvePath(path), content == null ? new byte[0] : content);
    }

    public void createFile(String path, String content) {
        writeFile(path, (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void deletePath(String path) {
        Path resolved = resolvePath(path);
        Path root = workspaceRoot();
        if (resolved.equals(root)) {
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
                new ModelInfo.ModelLimits(128_000, 8_192),
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


    private ToolSnapshot filterToolSnapshot(ToolSnapshot global, EngineConfig.AgentConfig agentConfig) {
        if (global == null) return ToolSnapshot.empty();
        if (agentConfig == null) return global;
        List<String> allow = agentConfig.toolAllow();
        List<String> deny = agentConfig.toolDeny();
        List<String> mcpServers = agentConfig.mcpServers();

        java.util.Set<String> allowedNames = new java.util.LinkedHashSet<>();
        for (ToolDefinition def : global.list()) {
            String name = def.effectiveName();
            if (SubmitResult.TOOL_NAME.equals(name)) {
                allowedNames.add(name);
                continue;
            }
            if (!allowed(name, allow)) continue;
            if (denied(name, deny)) continue;
            if (isMcpTool(name) && !mcpAllowed(name, mcpServers)) continue;
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

    private static boolean isMcpTool(String name) {
        return name != null && name.contains("_")
                && !name.equals("read") && !name.equals("write") && !name.equals("edit")
                && !name.equals("patch") && !name.equals("glob") && !name.equals("grep")
                && !name.equals("shell") && !name.equals("skill") && !name.equals("subagent");
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
            TurnOutcome.Refused, TurnOutcome.Failed, TurnOutcome.Terminated, TurnOutcome.Cancelled {

        record Completed(String finish, long inputTokens, long outputTokens, String text,
                         Option<JsonValue> structured) implements TurnOutcome {}

        record Busy(String message) implements TurnOutcome {}

        record Refused(String reason, String detail) implements TurnOutcome {}

        record Failed(SessionError error, boolean retryable) implements TurnOutcome {}

        record Terminated(InterruptReason reason) implements TurnOutcome {}

        record Cancelled() implements TurnOutcome {}
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
