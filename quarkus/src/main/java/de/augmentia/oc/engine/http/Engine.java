package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.contract.provider.Modality;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.event.EventCodec;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.adapter.sql.*;
import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.*;
import de.augmentia.oc.engine.core.port.runtime.SessionWake;
import de.augmentia.oc.engine.runtime.context.AgentProfile;
import de.augmentia.oc.engine.runtime.context.ContextBuilder;
import de.augmentia.oc.engine.runtime.session.core.SessionService;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;
import de.augmentia.oc.engine.runtime.step.Step;
import de.augmentia.oc.engine.runtime.step.StepResult;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolAuthorizer;
import de.augmentia.oc.engine.runtime.tool.runtime.ToolRuntime;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
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

    @Inject
    EngineConfig config;

    private Clock clock;
    private SqlJournal journal;
    private SqlConversation conversation;
    private SqlSessions sessions;
    private SessionService sessionService;
    private ContextBuilder contextBuilder;
    private Step step;
    private final EventCodec codec = EventCodec.standard();
    private final java.util.concurrent.ConcurrentHashMap<SessionId, Thread> running =
            new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void wire() {
        clock = new SystemClock();
        journal = new SqlJournal(dataSource, clock);
        conversation = new SqlConversation(dataSource);
        sessions = new SqlSessions(dataSource);
        ClaimStore claims = new SqlClaims(dataSource);
        ApprovalStore approvals = new SqlApprovals(dataSource);
        InstructionStore instructions = new SqlInstructions(dataSource);
        ToolOutputStore toolOutput = new SqlToolOutput(dataSource, Path.of("tool-output"));
        SessionWake wake = session -> { };

        sessionService = new SessionService(journal, sessions, wake, sessions::removeInbox);
        ToolRuntime toolRuntime = new ToolRuntime(ToolSnapshot.empty(),
                ToolAuthorizer.defaultAuthorizer(), request -> { }, noopHooks(), toolOutput);
        contextBuilder = new ContextBuilder(conversation, SkillCatalog.empty());
        step = new Step(provider, toolRuntime);
    }

    public SessionId createSession() {
        Path directory = Path.of(config.workspace()).toAbsolutePath().normalize();
        Location location = new Location(directory, ProjectId.create(), Option.none());
        AgentId agent = agentCatalogue.primaryId();
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

    public PromptResult prompt(SessionId id, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        Option<SessionInfo> found = sessions.info(id);
        if (found.isEmpty()) throw new IllegalArgumentException("unknown session: " + id);

        long before = journal.history(id.value(), Seq.zero()).size();
        running.put(id, Thread.currentThread());
        try {
            return runPrompt(id, text);
        } finally {
            running.remove(id);
            publishNew(id, before);
        }
    }

    private PromptResult runPrompt(SessionId id, String text) {
        InboxId inboxId = InboxId.create();
        InboxItem item = new InboxItem.UserItem(text, List.of(), List.of(), List.of(), Map.of());
        sessionService.enqueue(id, inboxId, item);
        Seq enqueuedSeq = Seq.of(journal.history(id.value(), Seq.zero()).size());
        sessions.putInbox(new InboxEntry(inboxId, id, item,
                de.augmentia.oc.engine.core.model.Delivery.QUEUE, enqueuedSeq, clock.now()));
        sessionService.deliver(id);
        SessionInfo info = sessions.info(id).orElseThrow();

        Seq seq = Seq.of(journal.history(id.value(), Seq.zero()).size() + 1L);
        conversation.insert(id, seq, new Message.UserMessage(MessageId.create(), text,
                List.of(), List.of(), List.of(), Map.of(), clock.now()));

        AgentProfile agent = new AgentProfile(info.agent(), Option.none(), 10, List.of());
        ModelInfo model = modelInfo();
        var prepared = contextBuilder.prepare(info, agent, model, ToolSnapshot.empty(), Seq.zero(), 1);
        ToolContext context = new ToolContext(id, info.agent(), MessageId.create(), CallId.create());
        StepResult result = step.run(prepared, context);
        return switch (result) {
            case StepResult.Finished finished -> new PromptResult(finished.reason().name(),
                    finished.usage().input(), finished.usage().output(), text(finished.events()));
            case StepResult.Failed failed ->
                    throw new IllegalStateException("model call failed: " + failed.message());
            case StepResult.NeedsCompaction ignored ->
                    throw new IllegalStateException("context overflow: compaction required");
        };
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
        return sessions.list();
    }

    public record LocationDto(String directory, ProjectDto project) {}

    public record ProjectDto(String id, String directory, String canonical) {}


    private void publishNew(SessionId id, long before) {
        journal.history(id.value(), Seq.of(Math.max(0, before))).forEach(eventBus::publish);
    }

    public String encode(StoredEvent event) {
        return Json.encode(JsonValue.of(Map.of(
                "seq", JsonValue.of(event.seq().value()),
                "type", JsonValue.of(event.type()),
                "version", JsonValue.of(event.version()),
                "data", codec.encode(event.payload()),
                "created", JsonValue.of(event.time().toString()))));
    }

    private ModelInfo modelInfo() {
        return new ModelInfo(ModelId.create(), ProviderId.create(), List.of(),
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

    public record ConfigDto(String provider, String model, String workspace) {}

    public record ModelDto(String provider, String model, int context, int output) {}
}
