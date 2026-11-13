package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.AssistantTime;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SkillId;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers prompt folding, truncation and header construction of the title generator. */
class TitleGeneratorTest {

    private static final SessionId SESSION = SessionId.create();
    private static final ProjectId PROJECT = ProjectId.create();
    private static final ModelRef MODEL = ModelRef.of(
            de.augmentia.oc.engine.core.model.ProviderId.create(), ModelId.create());

    @Test
    void rejectsNullProviderAndModel() {
        assertThatThrownBy(() -> new TitleGenerator(null, MODEL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TitleGenerator(request -> Stream.empty(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstMessagePromptContainsTextAndProjectHeader() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        TitleGenerator generator = new TitleGenerator(capturing(captured, List.of(
                new ModelEvent.TextDelta("b", "  My Session  "))), MODEL);

        String title = generator.fromFirstMessage("Hello world", SESSION, PROJECT);

        assertThat(title).isEqualTo("My Session");
        ModelRequest request = captured.get();
        assertThat(request.messages()).hasSize(1);
        String prompt = ((ModelContent.Text) ((ModelMessage.User) request.messages().getFirst())
                .content().getFirst()).text();
        assertThat(prompt).contains("Hello world");
        assertThat(request.headers()).containsEntry("X-Project-Id", PROJECT.value());
        assertThat(request.headers()).containsKey("x-session-affinity");
    }

    @Test
    void firstMessageIsBoundedAndNullBecomesEmpty() {
        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        TitleGenerator generator = new TitleGenerator(capturing(captured, List.of()), MODEL);

        generator.fromFirstMessage("x".repeat(TitleGenerator.MAX_FIRST_MESSAGE_CHARS + 50), SESSION, PROJECT);

        String prompt = ((ModelContent.Text) ((ModelMessage.User) captured.get().messages().getFirst())
                .content().getFirst()).text();
        assertThat(prompt).contains("x".repeat(TitleGenerator.MAX_FIRST_MESSAGE_CHARS));
        assertThat(prompt).doesNotContain("x".repeat(TitleGenerator.MAX_FIRST_MESSAGE_CHARS + 1));

        generator.fromFirstMessage(null, SESSION, PROJECT);
    }

    @Test
    void conversationFoldsSupportedMessagesAndSkipsOthers() {
        ModelRef model = MODEL;
        List<Message> messages = List.of(
                new Message.UserMessage(MessageId.create(), "user", List.of(), List.of(), List.of(),
                        java.util.Map.of(), Instant.EPOCH),
                new Message.SyntheticMessage(MessageId.create(), "synthetic", Option.none(),
                        java.util.Map.of(), Instant.EPOCH),
                new Message.SystemMessage(MessageId.create(), "system", Option.none(),
                        java.util.Map.of(), Instant.EPOCH),
                new Message.SkillMessage(MessageId.create(), SkillId.create(), "skill", "skill text",
                        java.util.Map.of(), Instant.EPOCH),
                new Message.AssistantMessage(MessageId.create(), AgentId.create(), model,
                        List.of(new Content.TextContent("assistant", Instant.EPOCH),
                                new Content.ReasoningContent("ignored-reasoning", Instant.EPOCH)),
                        Option.none(), Option.none(), Option.none(), Option.none(),
                        null, null, AssistantTime.started(Instant.EPOCH)),
                new Message.AgentSwitched(MessageId.create(), AgentId.create(), AgentId.create(), Instant.EPOCH));

        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        TitleGenerator generator = new TitleGenerator(capturing(captured, List.of()), model);

        generator.fromConversation(messages, SESSION, PROJECT);

        String prompt = ((ModelContent.Text) ((ModelMessage.User) captured.get().messages().getFirst())
                .content().getFirst()).text();
        assertThat(prompt).contains("user\nsynthetic\nsystem\nskill text\nassistant");
        assertThat(prompt).doesNotContain("ignored-reasoning");
    }

    @Test
    void conversationContextIsBounded() {
        List<Message> messages = List.of(new Message.UserMessage(MessageId.create(),
                "y".repeat(TitleGenerator.MAX_CONTEXT_CHARS + 100), List.of(), List.of(), List.of(),
                java.util.Map.of(), Instant.EPOCH));

        AtomicReference<ModelRequest> captured = new AtomicReference<>();
        new TitleGenerator(capturing(captured, List.of()), MODEL).fromConversation(messages, SESSION, PROJECT);

        String prompt = ((ModelContent.Text) ((ModelMessage.User) captured.get().messages().getFirst())
                .content().getFirst()).text();
        assertThat(prompt).doesNotContain("y".repeat(TitleGenerator.MAX_CONTEXT_CHARS + 1));
    }

    @Test
    void fallsBackToTextEndedWhenNoDeltasArrive() {
        TitleGenerator generator = new TitleGenerator(
                request -> Stream.of(new ModelEvent.TextEnded("b", "  ended title  ")), MODEL);

        assertThat(generator.fromFirstMessage("x", SESSION, PROJECT)).isEqualTo("ended title");
    }

    @Test
    void prefersDeltasOverTextEndedAndIgnoresOtherEvents() {
        TitleGenerator generator = new TitleGenerator(request -> Stream.of(
                new ModelEvent.TextStarted("b"),
                new ModelEvent.TextDelta("b", "delta"),
                new ModelEvent.TextEnded("b", "ended"),
                new ModelEvent.ReasoningDelta("r", "ignored")), MODEL);

        assertThat(generator.fromFirstMessage("x", SESSION, PROJECT)).isEqualTo("delta");
    }

    @Test
    void emptyStreamYieldsEmptyTitle() {
        TitleGenerator generator = new TitleGenerator(request -> Stream.empty(), MODEL);
        assertThat(generator.fromFirstMessage("x", SESSION, PROJECT)).isEmpty();
        assertThat(generator.fromConversation(List.of(), SESSION, PROJECT)).isEmpty();
    }

    @Test
    void titleIsTruncatedToMaximumLength() {
        TitleGenerator generator = new TitleGenerator(request -> Stream.of(
                new ModelEvent.TextDelta("b", "z".repeat(TitleGenerator.MAX_TITLE_LENGTH + 20))), MODEL);

        assertThat(generator.fromFirstMessage("x", SESSION, PROJECT))
                .hasSize(TitleGenerator.MAX_TITLE_LENGTH);
    }

    @Test
    void nullSessionOrProjectIsRejected() {
        TitleGenerator generator = new TitleGenerator(request -> Stream.empty(), MODEL);
        assertThatThrownBy(() -> generator.fromFirstMessage("x", null, PROJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
        assertThatThrownBy(() -> generator.fromFirstMessage("x", SESSION, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projectId");
    }

    private static ProviderAdapter capturing(AtomicReference<ModelRequest> captured, List<ModelEvent> events) {
        return request -> {
            captured.set(request);
            return events.stream();
        };
    }
}
