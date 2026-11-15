package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.OutcomePayload;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.AssistantTime;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.IdleOutcome;
import de.augmentia.oc.engine.core.model.Location;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ShellInfo;
import de.augmentia.oc.engine.core.model.SkillId;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolCard;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.model.ToolState;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers every message and tool-card branch of the conversation projection. */
class ConversationFoldingCoverageTest {

    private static final ModelRef MODEL = ModelRef.of(ProviderId.create(), ModelId.create());

    @Test
    void foldsSyntheticSystemAndSkillMessagesAsUserText() {
        List<ModelMessage> folded = ConversationFolding.fold(List.of(
                new Message.SyntheticMessage(MessageId.create(), "synthetic", Option.none(), Map.of(), Instant.EPOCH),
                new Message.SystemMessage(MessageId.create(), "system", Option.none(), Map.of(), Instant.EPOCH),
                new Message.SkillMessage(MessageId.create(), SkillId.create(), "skill", "skill text",
                        Map.of(), Instant.EPOCH)));

        assertThat(folded).hasSize(3);
        assertThat(text(folded.get(0))).isEqualTo("synthetic");
        assertThat(text(folded.get(1))).isEqualTo("system");
        assertThat(text(folded.get(2))).isEqualTo("skill text");
    }

    @Test
    void foldsShellAgentModelLocationAndIdleEvents() {
        Location before = new Location(Path.of("/tmp/before"), ProjectId.create(), Option.none());
        Location after = new Location(Path.of("/tmp/after"), ProjectId.create(), Option.none());
        List<ModelMessage> folded = ConversationFolding.fold(List.of(
                new Message.ShellMessage(MessageId.create(),
                        new ShellInfo("sh-1", "ls -la", "running", Option.none()), Instant.EPOCH),
                new Message.AgentSwitched(MessageId.create(), AgentId.create(), AgentId.create(), Instant.EPOCH),
                new Message.ModelSwitched(MessageId.create(), MODEL, MODEL, Instant.EPOCH),
                new Message.LocationSwitched(MessageId.create(), after, before, Instant.EPOCH),
                new Message.IdleMessage(MessageId.create(), IdleOutcome.SUCCEEDED, Option.none(), Instant.EPOCH)));

        assertThat(folded).hasSize(5);
        assertThat(text(folded.get(0))).isEqualTo("Shell ls -la: running");
        assertThat(text(folded.get(1))).startsWith("Agent switched to ");
        assertThat(text(folded.get(2))).startsWith("Model switched to ");
        assertThat(text(folded.get(3))).isEqualTo("Location switched to /tmp/after");
        assertThat(text(folded.get(4))).isEqualTo("Idle: SUCCEEDED");
    }

    @Test
    void assistantFoldsReasoningTextAndCompletedToolCards() {
        CallId call = CallId.create();
        ToolName name = new ToolName("read");
        Message.AssistantMessage assistant = assistant(List.of(
                new Content.TextContent("answer", Instant.EPOCH),
                new Content.ReasoningContent("thought", Instant.EPOCH),
                new Content.ToolCardContent(new ToolCard(call, name, JsonValue.of(Map.of()),
                        new ToolState.Completed(JsonValue.of(Map.of()),
                                List.of(new ToolContent.TextItem("done")), Map.of("source", "cache")),
                        true, Instant.EPOCH))));

        List<ModelMessage> folded = ConversationFolding.fold(List.of(assistant));

        assertThat(folded).hasSize(2);
        ModelMessage.Assistant modelAssistant = (ModelMessage.Assistant) folded.get(0);
        assertThat(modelAssistant.content()).hasSize(3);
        assertThat(((ModelContent.ToolCall) modelAssistant.content().get(2)).name()).isEqualTo(name);

        ModelMessage.ToolResult result = (ModelMessage.ToolResult) folded.get(1);
        assertThat(result.callId()).isEqualTo(call);
        OutcomePayload.Content content = (OutcomePayload.Content) result.outcome();
        assertThat(((ToolContent.TextItem) content.items().getFirst()).text())
                .isEqualTo("<tool-result name=\"read\" id=\"" + call.value() + "\">done</tool-result>");
        assertThat(content.metadata().orElseThrow()).containsEntry("source", "cache");
    }

    @Test
    void assistantFoldsFailedToolCardsAsTextResult() {
        CallId call = CallId.create();
        ToolName name = new ToolName("read");
        Message.AssistantMessage assistant = assistant(List.of(
                new Content.ToolCardContent(new ToolCard(call, name, JsonValue.of(Map.of()),
                        new ToolState.Failed(JsonValue.ofNull(),
                                new SessionError.ToolExecutionError("tool_failure", "failed message"),
                                Option.none(), Map.of()), true, Instant.EPOCH))));

        List<ModelMessage> folded = ConversationFolding.fold(List.of(assistant));

        assertThat(folded).hasSize(2);
        OutcomePayload.Text outcome = (OutcomePayload.Text) ((ModelMessage.ToolResult) folded.get(1)).outcome();
        assertThat(outcome.text()).isEqualTo("<tool-result name=\"read\" id=\"" + call.value() + "\">failed message</tool-result>");
    }

    @Test
    void unfinishedToolCardsProduceNoResultMessage() {
        Message.AssistantMessage assistant = assistant(List.of(
                new Content.ToolCardContent(new ToolCard(CallId.create(), new ToolName("read"),
                        JsonValue.of(Map.of()), new ToolState.Running(JsonValue.ofNull(), Map.of()),
                        false, Instant.EPOCH))));

        List<ModelMessage> folded = ConversationFolding.fold(List.of(assistant));

        assertThat(folded).hasSize(1);
        assertThat(folded.getFirst()).isInstanceOf(ModelMessage.Assistant.class);
    }

    private static Message.AssistantMessage assistant(List<Content> content) {
        return new Message.AssistantMessage(MessageId.create(), AgentId.create(), MODEL, content,
                Option.none(), Option.none(), Option.none(), Option.none(), Money.zero(),
                TokenCount.zero(), AssistantTime.started(Instant.EPOCH));
    }

    private static String text(ModelMessage message) {
        ModelMessage.User user = (ModelMessage.User) message;
        return ((ModelContent.Text) user.content().getFirst()).text();
    }
}
