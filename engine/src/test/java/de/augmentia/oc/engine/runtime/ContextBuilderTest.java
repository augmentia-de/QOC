package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelInfo;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.Modality;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.port.world.InstructionLoader;
import de.augmentia.oc.engine.runtime.context.AgentProfile;
import de.augmentia.oc.engine.runtime.context.ContextBuilder;
import de.augmentia.oc.engine.runtime.context.Prepared;
import de.augmentia.oc.engine.runtime.skill.Skill;
import de.augmentia.oc.engine.runtime.skill.SkillCatalog;
import de.augmentia.oc.engine.testing.InMemoryConversation;
import de.augmentia.oc.engine.testing.InMemoryInstructions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderTest {

    private InMemoryConversation conversation;
    private SessionInfo session;
    private ModelInfo model;
    private ToolSnapshot tools;
    private SkillCatalog skills;

    @BeforeEach
    void setUp() {
        conversation = new InMemoryConversation();
        session = session(Seq.zero());
        model = model();
        tools = new ToolSnapshot(Map.of(new ToolName("read"),
                definition(new ToolName("read"), "read", ToolOptions.defaults())));
        skills = SkillCatalog.of(List.of(new Skill(SkillId.parse("commit"), "commit",
                "use when committing", "body", Path.of("/skills/commit"), List.of(), true)));
    }

    @Test
    void systemHasBaseAndInstructionBlock() {
        Prepared prepared = builder().prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 0);

        assertThat(prepared.system()).hasSize(2);
        assertThat(prepared.system().get(0)).contains("Available tools: read");
        assertThat(prepared.system().get(1)).contains("<available_skills>").contains("<id>commit</id>");
    }

    @Test
    void agentSystemReplacesTheBaseBlock() {
        Prepared prepared = builder().prepare(session, agent(Option.some("You are terse."), 0),
                model, tools, Seq.zero(), 0);
        assertThat(prepared.system().get(0)).isEqualTo("You are terse.");
    }

    @Test
    void instructionFilesExtendTheSystemBlocks() {
        InstructionLoader loader = directory -> List.of(
                new InstructionLoader.InstructionFile(Path.of("/work/AGENTS.md"),
                        "project rules", InstructionLoader.Kind.AGENTS),
                new InstructionLoader.InstructionFile(Path.of("/work/APPEND_SYSTEM.md"),
                        "appended rules", InstructionLoader.Kind.APPEND_SYSTEM));

        Prepared prepared = new ContextBuilder(conversation, skills, loader)
                .prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 0);

        assertThat(prepared.system()).anyMatch(text -> text.contains("project rules"));
        assertThat(prepared.system()).anyMatch(text -> text.contains("appended rules"));
    }

    @Test
    void systemInstructionReplacesTheBaseBlock() {
        InstructionLoader loader = directory -> List.of(
                new InstructionLoader.InstructionFile(Path.of("/work/SYSTEM.md"),
                        "custom system", InstructionLoader.Kind.SYSTEM));

        Prepared prepared = new ContextBuilder(conversation, skills, loader)
                .prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 0);

        assertThat(prepared.system().getFirst()).isEqualTo("custom system");
        assertThat(prepared.system()).noneMatch(text -> text.contains("Available tools:"));
    }

    @Test
    void compactionCheckpointIsASystemBlockNotMessageZero() {
        InMemoryInstructions store = new InMemoryInstructions();
        store.putBlob("summary-hash", "compacted summary");
        store.putState(session.id(), new InstructionState(Seq.of(1), Seq.of(1),
                Map.of(), Map.of("compaction.summary", "summary-hash")));

        Prepared prepared = new ContextBuilder(conversation, skills, InstructionLoader.none(),
                Option.some(store))
                .prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 0);

        assertThat(prepared.system()).anyMatch(text -> text.contains("compacted summary"));
        assertThat(prepared.messages()).noneMatch(message ->
                message instanceof ModelMessage.User user
                        && user.content().stream().anyMatch(content ->
                        content instanceof ModelContent.Text text
                                && text.text().contains("compacted summary")));
    }

    @Test
    void deliveredHistoryIsMappedToModelMessages() {
        conversation.add(session.id(), Seq.of(1), new Message.UserMessage(MessageId.create(),
                "hello", List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        conversation.add(session.id(), Seq.of(2), new Message.AssistantMessage(MessageId.create(),
                AgentId.create(), ref(), List.of(new Content.TextContent("hi", Instant.EPOCH)),
                Option.none(), Option.none(), Option.none(), Option.none(), Money.zero(),
                TokenCount.zero(), AssistantTime.started(Instant.EPOCH)));

        Prepared prepared = builder().prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 0);

        assertThat(prepared.messages()).hasSize(2);
        assertThat(prepared.messages().get(0)).isInstanceOf(ModelMessage.User.class);
        assertThat(prepared.messages().get(1)).isInstanceOf(ModelMessage.Assistant.class);
    }

    @Test
    void toolCardBecomesCallAndResultMessages() {
        ToolCard card = new ToolCard(CallId.create(), new ToolName("read"), JsonValue.ofNull(),
                new ToolState.Completed(JsonValue.ofNull(),
                        List.of(new ToolContent.TextItem("content")), Map.of()), false, Instant.EPOCH);
        conversation.add(session.id(), Seq.of(1), new Message.AssistantMessage(MessageId.create(),
                AgentId.create(), ref(), List.of(new Content.ToolCardContent(card)),
                Option.none(), Option.none(), Option.none(), Option.none(), Money.zero(),
                TokenCount.zero(), AssistantTime.started(Instant.EPOCH)));

        Prepared prepared = builder().prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 0);

        assertThat(prepared.messages()).hasSize(2);
        assertThat(prepared.messages().get(0)).isInstanceOf(ModelMessage.Assistant.class);
        assertThat(prepared.messages().get(1)).isInstanceOf(ModelMessage.ToolResult.class);
    }

    @Test
    void stepLimitAddsAssistantPromptAndDisablesTools() {
        Prepared prepared = builder().prepare(session, agent(Option.none(), 2), model, tools, Seq.zero(), 2);

        assertThat(prepared.toolChoice().orElseThrow()).isInstanceOf(Choice.None.class);
        ModelMessage last = prepared.messages().getLast();
        assertThat(last).isInstanceOf(ModelMessage.Assistant.class);
        assertThat(((ModelContent.Text) ((ModelMessage.Assistant) last).content().getFirst()).text())
                .isEqualTo(ContextBuilder.STEP_LIMIT_PROMPT);
    }

    @Test
    void zeroMaxStepsDisablesStepLimit() {
        Prepared prepared = builder().prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 100);

        assertThat(prepared.toolChoice().isEmpty()).isTrue();
        assertThat(prepared.messages()).noneMatch(message ->
                message instanceof ModelMessage.Assistant assistant
                        && assistant.content().stream().anyMatch(content ->
                        content instanceof ModelContent.Text text
                                && ContextBuilder.STEP_LIMIT_PROMPT.equals(text.text())));
    }

    @Test
    void forcedToolChoiceSkipsStepLimitPrompt() {
        Prepared prepared = builder().prepare(session, agent(Option.none(), 2), model, tools,
                Seq.zero(), 2, Option.some(new Choice.One(new ToolName("submit_result"))));

        assertThat(prepared.toolChoice().orElseThrow()).isInstanceOf(Choice.One.class);
        assertThat(prepared.messages()).noneMatch(message ->
                message instanceof ModelMessage.Assistant assistant
                        && assistant.content().stream().anyMatch(content ->
                        content instanceof ModelContent.Text text
                                && ContextBuilder.STEP_LIMIT_PROMPT.equals(text.text())));
    }

    @Test
    void budgetAndAffinityAreSet() {
        Prepared prepared = builder().prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 0);

        assertThat(prepared.generation().maxOutputTokens().orElseThrow()).isEqualTo(500);
        assertThat(prepared.headers()).containsKey("x-session-affinity").containsKey("X-Project-Id");
        assertThat(prepared.cacheKey().orElseThrow()).isEqualTo(session.id().value().substring(4));
    }

    @Test
    void requestCarriesExactlyThePreparedData() {
        Prepared prepared = builder().prepare(session, agent(Option.none(), 0), model, tools, Seq.zero(), 0);
        var request = prepared.toRequest();
        assertThat(request.model()).isEqualTo(prepared.model());
        assertThat(request.system()).isEqualTo(prepared.system());
        assertThat(request.tools()).isEqualTo(prepared.tools());
        assertThat(request.headers()).isEqualTo(prepared.headers());
    }

    private ContextBuilder builder() {
        return new ContextBuilder(conversation, skills);
    }

    private AgentProfile agent(Option<String> system, int maxSteps) {
        return new AgentProfile(AgentId.create(), system, maxSteps, List.of());
    }

    private SessionInfo session(Seq unused) {
        return new SessionInfo(SessionId.create(), ProjectId.create(),
                new Location(Path.of("/work"), ProjectId.create(), Option.none()), "title",
                AgentId.create(), Option.some(ref()), List.of(), Money.zero(), TokenCount.zero(),
                Instant.EPOCH, Instant.EPOCH, Option.none(), Option.none(), 0, Option.none());
    }

    private ModelInfo model() {
        return new ModelInfo(ModelId.create(), ProviderId.create(), List.of(),
                new ModelInfo.ModelLimits(100_000, 500),
                new ModelInfo.CostModel(Money.zero(), Money.zero(), Money.zero(), Money.zero()),
                new ModelInfo.ModelCapabilities(List.of(Modality.TEXT), List.of(Modality.TEXT)),
                Option.none(), Option.none());
    }

    private ModelRef ref() {
        return ModelRef.of(ProviderId.create(), ModelId.create());
    }

    private ToolDefinition definition(ToolName name, String description, ToolOptions options) {
        return new ToolDefinition(name, description, Schema.obj(Map.of()), Option.none(),
                (input, context) -> new ToolOutcome.Ok(ToolResult.of("ok")), options);
    }
}
