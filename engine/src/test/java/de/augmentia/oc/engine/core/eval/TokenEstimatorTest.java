package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.AssistantTime;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.CompactionReason;
import de.augmentia.oc.engine.core.model.CompactionStatus;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.IdleOutcome;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    private static final MessageId ID = MessageId.create();

    @Test
    void estimatesPlainText() {
        assertThat(TokenEstimator.estimate((String) null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate("a")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("1234567")).isEqualTo(2);
        assertThat(TokenEstimator.estimate("12345678")).isEqualTo(3);
    }

    @Test
    void estimatesSimpleMessageKinds() {
        assertThat(TokenEstimator.estimate((Message) null)).isZero();
        assertThat(TokenEstimator.estimate(new Message.UserMessage(ID, "1234567",
                List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH))).isEqualTo(6);
        assertThat(TokenEstimator.estimate(new Message.SyntheticMessage(ID, "1234567",
                Option.none(), Map.of(), Instant.EPOCH))).isEqualTo(6);
        assertThat(TokenEstimator.estimate(new Message.SystemMessage(ID, "1234567",
                Option.none(), Map.of(), Instant.EPOCH))).isEqualTo(6);
        assertThat(TokenEstimator.estimate(new Message.SkillMessage(ID, SkillId.create(), "skill",
                "1234567", Map.of(), Instant.EPOCH))).isEqualTo(6);
        assertThat(TokenEstimator.estimate(new Message.ShellMessage(ID,
                new ShellInfo("s1", "1234567", "done", Option.none()), Instant.EPOCH))).isEqualTo(6);
        assertThat(TokenEstimator.estimate(new Message.CompactionMessage(ID,
                CompactionStatus.COMPLETED, CompactionReason.MANUAL, "1234567",
                Option.none(), Money.zero(), TokenCount.zero(), Instant.EPOCH))).isEqualTo(6);
    }

    @Test
    void estimatesAssistantTextContent() {
        Message.AssistantMessage message = assistant(List.of(
                new Content.TextContent("1234567", Instant.EPOCH),
                new Content.ReasoningContent("ignored", Instant.EPOCH)));

        assertThat(TokenEstimator.estimate(message)).isEqualTo(6);
    }

    @Test
    void estimatesToolCardsInAllStates() {
        ToolName name = new ToolName("read");
        JsonValue input = JsonValue.of("x");
        int cardBaseline = 4 + TokenEstimator.estimate("read") + TokenEstimator.estimate(input.toString());

        Message.AssistantMessage streaming = assistant(List.of(new Content.ToolCardContent(
                card(name, input, new ToolState.Streaming("x")))));
        assertThat(TokenEstimator.estimate(streaming)).isEqualTo(4 + cardBaseline);

        Message.AssistantMessage running = assistant(List.of(new Content.ToolCardContent(
                card(name, input, new ToolState.Running(input, Map.of())))));
        assertThat(TokenEstimator.estimate(running)).isEqualTo(4 + cardBaseline);

        Message.AssistantMessage completed = assistant(List.of(new Content.ToolCardContent(
                card(name, input, new ToolState.Completed(input, List.of(
                        new ToolContent.TextItem("1234567"),
                        new ToolContent.FileItem("file:///a", "text/plain", Option.none())), Map.of())))));
        assertThat(TokenEstimator.estimate(completed))
                .isEqualTo(4 + cardBaseline + TokenEstimator.estimate("1234567"));

        Message.AssistantMessage failed = assistant(List.of(new Content.ToolCardContent(
                card(name, input, new ToolState.Failed(input, new SessionError.Unknown("x", "1234567"),
                        Option.none(), Map.of())))));
        assertThat(TokenEstimator.estimate(failed))
                .isEqualTo(4 + cardBaseline + TokenEstimator.estimate("1234567"));
    }

    @Test
    void ignoresSwitchAndIdleMessages() {
        AgentId agent = AgentId.create();
        assertThat(TokenEstimator.estimate(new Message.AgentSwitched(ID, agent, agent, Instant.EPOCH)))
                .isEqualTo(4);
        assertThat(TokenEstimator.estimate(new Message.IdleMessage(ID, IdleOutcome.SUCCEEDED,
                Option.none(), Instant.EPOCH))).isEqualTo(4);
    }

    private static Message.AssistantMessage assistant(List<Content> content) {
        return new Message.AssistantMessage(ID, AgentId.create(),
                ModelRef.of(ProviderId.create(), ModelId.create()), content,
                Option.none(), Option.none(), Option.none(), Option.none(),
                Money.zero(), TokenCount.zero(), AssistantTime.started(Instant.EPOCH));
    }

    private static ToolCard card(ToolName name, JsonValue input, ToolState state) {
        return new ToolCard(CallId.create(), name, input, state, true, Instant.EPOCH);
    }
}
