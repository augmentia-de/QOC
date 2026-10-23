package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.model.CompactionReason;
import de.augmentia.oc.engine.core.model.CompactionStatus;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.AssistantTime;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.ModelId;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProviderId;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.runtime.context.ConversationFolding;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationFoldingTest {

    @Test
    void foldsUserAndAssistantMessages() {
        Message.UserMessage user = new Message.UserMessage(MessageId.create(), "hello",
                List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH);
        Message.AssistantMessage assistant = new Message.AssistantMessage(MessageId.create(),
                AgentId.create(), ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of(new Content.TextContent("hi", Instant.EPOCH)), Option.none(), Option.none(),
                Option.none(), Option.none(), Money.zero(), TokenCount.zero(),
                AssistantTime.started(Instant.EPOCH));

        var folded = ConversationFolding.fold(List.of(user, assistant));

        assertThat(folded).hasSize(2);
        assertThat(folded.get(0)).isInstanceOf(ModelMessage.User.class);
        assertThat(folded.get(1)).isInstanceOf(ModelMessage.Assistant.class);
    }

    @Test
    void wrapsCompactionMessageInCheckpoint() {
        Message.CompactionMessage compaction = new Message.CompactionMessage(MessageId.create(),
                CompactionStatus.COMPLETED, CompactionReason.MANUAL, "summary text",
                Option.none(), Money.zero(), TokenCount.zero(), Instant.EPOCH);

        var folded = ConversationFolding.fold(List.of(compaction));

        assertThat(folded).hasSize(1);
        assertThat(folded.getFirst()).isInstanceOf(ModelMessage.User.class);
        String text = ((ModelMessage.User) folded.getFirst()).content().getFirst() instanceof
                de.augmentia.oc.engine.core.contract.provider.ModelContent.Text t ? t.text() : "";
        assertThat(text).contains("<conversation-checkpoint>");
        assertThat(text).contains("summary text");
    }

    @Test
    void nullAndEmptyYieldEmpty() {
        assertThat(ConversationFolding.fold(null)).isEmpty();
        assertThat(ConversationFolding.fold(List.of())).isEmpty();
    }
}
