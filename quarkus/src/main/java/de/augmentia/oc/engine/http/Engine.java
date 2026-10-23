package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.contract.provider.Modality;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.event.ContractCodec;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.event.EventCodec;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
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
import de.augmentia.oc.engine.adapter.fs.NioFileSystem;
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
import de.augmentia.oc.engine.runtime.tool.shell.ShellTool;
import de.augmentia.oc.engine.runtime.tool.subagent.SubagentTool;
import de.augmentia.oc.engine.runtime.tool.fs.WriteTool;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;
import de.augmentia.oc.engine.runtime.permission.engine.PermissionEngine;
import de.augmentia.oc.engine.runtime.tool.mcp.McpInstructions;
import de.augmentia.oc.engine.runtime.tool.mcp.McpServerManager;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.human.PolicySource;
import de.augmentia.oc.engine.core.port.store.*;
import de.augmentia.oc.engine.core.port.store.SessionStore;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.core.port.runtime.SessionWake;
import de.augmentia.oc.engine.runtime.context.AgentProfile;
import de.augmentia.oc.engine.runtime.context.ContextBuilder;
import de.augmentia.oc.engine.runtime.context.TitleGenerator;
import de.augmentia.oc.engine.runtime.compaction.Compaction;
import de.augmentia.oc.engine.runtime.compaction.CompactionService;
import de.augmentia.oc.engine.runtime.session.core.SessionService;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;
import de.augmentia.oc.engine.runtime.step.Step;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.eval.PermissionCheck;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;

import jakarta.annotation.PostConstruct;
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

/** Wiring of the engine for the Quarkus outer skin (M14). */
@ApplicationScoped
public class Engine {

    @Inject
    DataSource dataSource;

    @Inject
    de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider;

    @Inject
    EventBus eventBus;

    @Inject
    AgentCatalogue agentCatalogue;

    private static final Logger LOG = Logger.getLogger(Engine.class);

    @Inject
    Tracer tracer;

    @Inject
    EngineConfig config;

    @Inject
    @ConfigProperty(name = "engine.db.kind", defaultValue = "postgresql")
    String dbKind;

    @Inject
    WebApprovalSource approvalSource;

    private Clock clock;
    private Journal journal;
    private ConversationStore conversation;
    private SessionStore sessions;
    private SessionService sessionService;
    private ContextBuilder contextBuilder;
    private TitleGenerator titleGenerator;
    private Step step;
    private ToolSnapshot toolSnapshot;
    private ModelRef modelRef;    private NioFileSystem fileSystem;
    private InstructionStore instructions;
    private CompactionService compaction;
    private McpServerManager mcp;
    private List<Rule> allowAll;
    private SubagentTool subagentTool;
    private PermissionEngine permissionEngine;
    private final EventCodec codec = EventCodec.standard();
    private final java.util.concurrent.ConcurrentHashMap<SessionId, Thread> running =
            new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void wire() {
        clock = new SystemClock();
        boolean sqlite = "sqlite".equalsIgnoreCase(dbKind);
        if (sqlite) Sqlite.enableWal(dataSource);
        journal = sqlite ? new SqliteJournal(dataSource, clock) : new SqlJournal(dataSource, clock);
        conversation = sqlite ? new SqliteConversation(dataSource) : new SqlConversation(dataSource);
        sessions = sqlite ? new SqliteSessions(dataSource) : new SqlSessions(dataSource);
        ClaimStore claims = sqlite ? new SqliteClaims(dataSource) : new SqlClaims(dataSource);
        ApprovalStore approvals = sqlite ? new SqliteApprovals(dataSource) : new SqlApprovals(dataSource);
        instructions = sqlite ? new SqliteInstructions(dataSource) : new SqlInstructions(dataSource);
        ToolOutputStore toolOutput = sqlite
                ? new SqliteToolOutput(dataSource, Path.of("tool-output"))
                : new SqlToolOutput(dataSource, Path.of("tool-output"));
        SessionWake wake = session -> { };

        sessionService = new SessionService(journal, sessions, wake, sessions::removeInbox);
        ApprovalStore permissionApprovals = sqlite ? new SqliteApprovals(dataSource) : new SqlApprovals(dataSource);
        CredentialStore credentialStore = sqlite ? new SqliteCredentials(dataSource) : new SqlCredentials(dataSource);
        permissionEngine = new PermissionEngine(Option.some(permissionApprovals),
                Option.some(approvalSource), Option.some((PolicySource) () -> List.of()), Option.none());

        Path workspacePath = Path.of(config.workspace()).toAbsolutePath().normalize();
        fileSystem = new NioFileSystem(workspacePath);
        NioFileSystem fs = fileSystem;
        FileAccess access = new FileAccess(fs, workspacePath, workspacePath);
        PermissionGate permissionGate = request -> {
            SessionInfo info = sessions.info(request.sessionId()).orElse(null);
            List<Rule> sessionRules = info == null ? List.of() : info.permissions();
            Option<AgentId> agent = info == null ? Option.none() : Option.some(info.agent());
            LOG.debugf("permission check session=%s action=%s", request.sessionId().value(), request.action());
            Span pspan = tracer.spanBuilder("permission.assert")
                    .setAttribute("session.id", request.sessionId().value())
                    .setAttribute("action", request.action())
                    .startSpan();
            try (Scope pscope = pspan.makeCurrent()) {
            permissionEngine.assertAllowed(new PermissionCheck(request, agent,
                    List.of(new Rule("*", "*", Effect.ALLOW), new Rule("grep", "*", Effect.ASK)), sessionRules, List.of(), List.of()));
            pspan.setAttribute("result", "checked");
            pspan.end();
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(ReadTool.definition(fs, access, permissionGate));
        registry.register(WriteTool.definition(fs, access, permissionGate));
        registry.register(EditTool.definition(fs, access, permissionGate));
        registry.register(PatchTool.definition(fs, access, permissionGate));
        registry.register(GlobTool.definition(fs, access, permissionGate));
        registry.register(GrepTool.definition(fs, access, permissionGate));
        registry.register(ShellTool.definition(new JdkProcessRunner(), workspacePath, workspacePath, null, permissionGate));
        subagentTool = new SubagentTool(null, permissionGate);
        registry.register(SubagentTool.definition(subagentTool));
        allowAll = List.of(new Rule("*", "*", Effect.ALLOW));
        mcp = new McpServerManager(registry, new JdkHttpTransport(), new JdkProcessRunner(),
                Option.some(credentialStore));
        for (de.augmentia.oc.engine.runtime.tool.mcp.McpServerConfig server : config.mcpServers()) {
            mcp.add(server);
        }
        toolSnapshot = registry.snapshot(allowAll);
        ToolRuntime toolRuntime = new ToolRuntime(toolSnapshot, ToolAuthorizer.defaultAuthorizer(),
                permissionGate, noopHooks(), toolOutput);
        contextBuilder = new ContextBuilder(conversation, SkillCatalog.empty());
        modelRef = new ModelRef(ProviderId.create(), ModelId.create());
        titleGenerator = new TitleGenerator(provider, modelRef);
        step = new Step(provider, toolRuntime);
        if (subagentTool != null) {
            subagentTool.setRunner((parent, agentName, prompt, depth) ->
                    runSubagent(parent, agentName, prompt, depth));
        }
        compaction = new CompactionService(journal, conversation, instructions,
                new Compaction(journal, provider), clock, 8, provider);
    }

    public SessionId createSession() {
        return createSession(null);
    }

    public SessionId createSession(String agentName) {
        Path directory = Path.of(config.workspace()).toAbsolutePath().normalize();
        Location location = new Location(directory, ProjectId.create(), Option.none());
        AgentId agent = agentName == null ? agentCatalogue.primaryId()
                : agentCatalogue.list().stream()
                        .filter(a -> a.name().equalsIgnoreCase(agentName))
                        .findFirst()
                        .map(AgentCatalogue.AgentDto::id)
                        .map(AgentId::parse)
                        .orElse(agentCatalogue.primaryId());
        Instant now = clock.now();
        SessionId id = sessionService.create(location, agent, Option.none(), now);
        sessions.put(new SessionInfo(id, location.projectId(), location, "New session", agent,
                Option.none(), List.of(), Money.zero(), TokenCount.zero(), now, now,
                Option.none(), Option.none(), 0, Option.none()));
        return id;
    }

    public Option<SessionInfo> session(SessionId id) {
        return sessions.info(id);
    }

    public List<PermissionRequest> pendingPermissions(SessionId id) {
        return permissionEngine.pending().list(id);
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

    public String generateTitle(SessionId id) {
        if (titleGenerator == null) return null;
        List<Message> messages = conversation.messages(id, Seq.zero());
        if (messages.isEmpty()) return null;
        String title = titleGenerator.fromConversation(messages);
        // Update the session title via a new event.
        journal.append(new EventDraft(id.value(), 1,
                new SessionEvent.SessionRenamed(id, title)));
        SessionInfo info = sessions.info(id).orElse(null);
        if (info != null) {
            sessions.put(new SessionInfo(info.id(), info.projectId(), info.location(), title,
                    info.agent(), info.model(), info.permissions(), info.cost(), info.tokens(),
                    info.created(), clock.now(), info.idle(), info.suspended(),
                    info.resumeAttempts(), info.archived()));
        }
        return title;
    }

    public PromptResult prompt(SessionId id, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        LOG.infof("prompt start session=%s text=%s", id.value(), text.length() > 120 ? text.substring(0,120) + "..." : text);
        Option<SessionInfo> found = sessions.info(id);
        if (found.isEmpty()) throw new IllegalArgumentException("unknown session: " + id);

        long before = journal.history(id.value(), Seq.zero()).size();
        running.put(id, Thread.currentThread());
        Span span = tracer.spanBuilder("engine.prompt")
                .setAttribute("session.id", id.value())
                .setAttribute("text.length", (long) text.length())
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            try {
                PromptResult r = runPrompt(id, text);
                span.setAttribute("finish", r.finish());
                span.setAttribute("usage.input_tokens", (long) r.inputTokens());
                span.setAttribute("usage.output_tokens", (long) r.outputTokens());
                LOG.infof("prompt finish session=%s finish=%s in=%d out=%d", id.value(), r.finish(), r.inputTokens(), r.outputTokens());
                return r;
            } catch (RuntimeException e) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR, e.getMessage());
                throw e;
            } finally {
                running.remove(id);
                publishNew(id, before);
            }
        } finally {
            span.end();
        }
    }

    public PromptResult runPrompt(SessionId id, String text) {
        return runPrompt(id, text, null);    }

    public PromptResult runPrompt(SessionId id, String text, AgentProfile agentOverride) {
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
        AgentProfile agent = agentOverride != null ? agentOverride
                : AgentProfile.forAgent(info.agent(), Option.none(), 10,
                        List.of(new Rule("*", "*", Effect.ALLOW), new Rule("grep", "*", Effect.ASK)), agentName);

        // MODE-PLAN: log synthetic message when entering/leaving plan mode (PR-7).
        if (agent.planMode()) {
            journal.append(new EventDraft(id.value(), 1,
                    new SessionEvent.SyntheticRaised(id,
                            PromptLoader.get("mode-plan-enter"), Option.none(), Map.of("mode", "plan"))));
        }

        ModelInfo model = modelInfo();
        var liveSink = (java.util.function.Consumer<de.augmentia.oc.engine.core.contract.provider.ModelEvent>) event ->
                eventBus.publishLive(id.value(), Json.encode(ContractCodec.encode(event,
                        de.augmentia.oc.engine.core.contract.provider.ModelEvent.class)));

        int stepNo = 1;
        StepResult.Finished finished = null;
        FinishReason finishReason = FinishReason.NORMAL;
        TokenCount usage = TokenCount.zero();
        String finalText = "";

        while (true) {
            LOG.debugf("step %d session=%s", stepNo, id.value());
            Span stepSpan = tracer.spanBuilder("engine.step")
                    .setAttribute("session.id", id.value())
                    .setAttribute("step", (long) stepNo)
                    .startSpan();
            try (Scope stepScope = stepSpan.makeCurrent()) {
            var prepared = contextBuilder.prepare(info, agent, model, toolSnapshot, Seq.zero(), stepNo);
            ToolContext context = new ToolContext(id, info.agent(), MessageId.create(), CallId.create());
            StepResult result = step.run(prepared, context, liveSink);
            if (result instanceof StepResult.NeedsCompaction) {
                // Automatic compaction (I-07, I-09): exactly one per step.
                Span cspan = tracer.spanBuilder("compaction").setAttribute("session.id", id.value()).startSpan();
                try (Scope cscope = cspan.makeCurrent()) {
                compaction.compact(id, CompactionReason.OVERFLOW, preparedModel(info, model),
                        Option.none(), Seq.of(journal.history(id.value(), Seq.zero()).size()),
                        clock.now());
                cspan.end();
                }
                prepared = contextBuilder.prepare(info, agent, model, toolSnapshot, Seq.zero(), stepNo);
                result = step.run(prepared, context, liveSink);
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
                    state = new ToolState.Failed(call.input(), failed.error(), Option.none(), Map.of());
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

            boolean hasToolCalls = !finished.toolCalls().isEmpty();
            if (!hasToolCalls) break;
            if (stepNo >= agent.maxSteps()) break;
            stepNo++;
        }

        PromptResult promptResult = new PromptResult(finishReason.name(),
                usage.input(), usage.output(), finalText);

        // Generate title after the first successful prompt if not yet set.
        // Generate title after the first successful prompt if not yet set.
        if (info.title().equals("New session") || info.title().isBlank()) {
            generateTitle(id);
        }
        return promptResult;
    }

    private final java.util.Map<SessionId, Integer> subagentDepths = new java.util.concurrent.ConcurrentHashMap<>();

    public String runSubagent(SessionId parent, String agentName, String prompt, int depth) {
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

        Path directory = Path.of(config.workspace()).toAbsolutePath().normalize();
        Location location = new Location(directory, ProjectId.create(), Option.none());
        AgentProfile agent = AgentProfile.forAgent(agentId, Option.none(), 10,
                List.of(new Rule("*", "*", Effect.ALLOW), new Rule("grep", "*", Effect.ASK)), agentName);

        SessionId child = sessionService.create(location, agentId, Option.none(), clock.now());
        sessions.put(new SessionInfo(child, location.projectId(), location, "Subagent: " + agentName, agentId,
                Option.none(), List.of(), Money.zero(), TokenCount.zero(), clock.now(), clock.now(),
                Option.none(), Option.none(), 0, Option.none()));

        // Track depth
        subagentDepths.put(child, parentDepth + 1);

        PromptResult result = runPrompt(child, prompt, agent);
        return result.text() == null || result.text().isBlank()
                ? "Subagent completed with no text output."
                : result.text();
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

    public List<AgentCatalogue.AgentDto> agents() {
        return agentCatalogue.list();
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
        Path resolved = fileSystem.realPath(Path.of(config.workspace()).toAbsolutePath().normalize().resolve(target));
        List<FileEntry> entries = new ArrayList<>();
        for (Path child : fileSystem.list(resolved)) {
            try {
                FileSystem.FileStat stat = fileSystem.stat(child);
                entries.add(new FileEntry(child.getFileName().toString(), child.toString(),
                        stat.directory(), stat.size()));
            } catch (Exception ignored) { }
        }
        return entries;
    }

    public String readFile(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path must not be blank");
        Path resolved = fileSystem.realPath(Path.of(config.workspace()).toAbsolutePath().normalize().resolve(path));
        return fileSystem.readText(resolved);
    }

    public void createFile(String path, String content) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path must not be blank");
        Path resolved = Path.of(config.workspace()).toAbsolutePath().normalize().resolve(path);
        fileSystem.write(resolved, (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public void deleteFile(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path must not be blank");
        Path resolved = fileSystem.realPath(Path.of(config.workspace()).toAbsolutePath().normalize().resolve(path));
        try { java.nio.file.Files.delete(resolved); }
        catch (java.io.IOException e) { throw new RuntimeException("cannot delete: " + path, e); }
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

    public record PromptResult(String finish, long inputTokens, long outputTokens, String text) {}

    public record FileEntry(String name, String path, boolean directory, long size) {}

    public record ConfigDto(String provider, String model, String workspace) {}

    public record ModelDto(String provider, String model, int context, int output) {}
}
