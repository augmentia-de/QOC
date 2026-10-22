package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.CompactionReason;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.runtime.compaction.Compaction;
import de.augmentia.oc.engine.runtime.compaction.CompactionContext;
import de.augmentia.oc.engine.runtime.compaction.CompactionResult;
import de.augmentia.oc.engine.runtime.compaction.CompactionService;
import de.augmentia.oc.engine.runtime.compaction.CompactionWindow;
import de.augmentia.oc.engine.testing.FixedClock;
import de.augmentia.oc.engine.testing.InMemoryConversation;
import de.augmentia.oc.engine.testing.InMemoryInstructions;
import de.augmentia.oc.engine.testing.InMemoryJournal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionServiceTest {

    private InMemoryJournal journal;
    private InMemoryConversation conversation;
    private InMemoryInstructions instructions;
    private SessionId session;
    private ModelRef model;

    @BeforeEach
    void setUp() {
        journal = new InMemoryJournal();
        conversation = new InMemoryConversation();
        instructions = new InMemoryInstructions();
        session = SessionId.create();
        model = ModelRef.of(ProviderId.create(), ModelId.create());
    }

    @Test
    void compactsAndAdvancesTheEpoch() {
        for (int i = 0; i < 5; i++) {
            conversation.add(session, Seq.of(i + 1),
                    new Message.UserMessage(MessageId.create(), "msg " + i,
                            List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        }
        Compaction compaction = compaction(journal, provider(List.of(
                new ModelEvent.TextEnded("b", "summary text"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.of(1, 2), JsonValue.ofNull()))));
        CompactionService service = new CompactionService(journal, conversation, instructions,
                compaction, new FixedClock(Instant.EPOCH));

        CompactionResult result = service.compact(session, CompactionReason.MANUAL, model,
                Option.none(), Seq.of(5), Instant.EPOCH);

        assertThat(result).isInstanceOf(CompactionResult.Compacted.class);
        assertThat(((CompactionResult.Compacted) result).summary()).isEqualTo("summary text");
        assertThat(journal.historyOfType(session.value(), "CompactionStarted", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session.value(), "CompactionEnded", Seq.zero())).hasSize(1);
        assertThat(instructions.state(session).orElseThrow().epochStart()).isEqualTo(Seq.of(5));
        assertThat(instructions.blob(Integer.toHexString("summary text".hashCode())).orElseThrow())
                .isEqualTo("summary text");
    }

    @Test
    void windowSplitKeepsTheLastMessages() {
        List<Message> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new Message.UserMessage(MessageId.create(), "msg " + i,
                    List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        }

        CompactionWindow.Window window = CompactionWindow.split(history, 3);

        assertThat(window.toSummarize()).hasSize(7);
        assertThat(window.toKeep()).hasSize(3);
        assertThat(((Message.UserMessage) window.toKeep().getFirst()).text()).isEqualTo("msg 7");
    }

    @Test
    void emptyHistoryCompactsWithEmptySummary() {
        Compaction compaction = compaction(journal, provider(List.of(
                new ModelEvent.TextEnded("b", "empty"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))));
        CompactionService service = new CompactionService(journal, conversation, instructions,
                compaction, new FixedClock(Instant.EPOCH));

        CompactionResult result = service.compact(session, CompactionReason.RESTART, model,
                Option.none(), Seq.of(1), Instant.EPOCH);

        assertThat(result).isInstanceOf(CompactionResult.Compacted.class);
        assertThat(instructions.state(session).orElseThrow().initialValues()).isEmpty();
    }

    @Test
    void providerFailurePublishesCompactionFailed() {
        Compaction compaction = compaction(journal, provider(List.of(
                new ModelEvent.ProviderFailed(new FailureKind.RateLimited(Option.none()), "slow",
                        Option.none(), Option.none()))));
        CompactionService service = new CompactionService(journal, conversation, instructions,
                compaction, new FixedClock(Instant.EPOCH));

        CompactionResult result = service.compact(session, CompactionReason.MANUAL, model,
                Option.none(), Seq.of(1), Instant.EPOCH);

        assertThat(result).isInstanceOf(CompactionResult.Failed.class);
        assertThat(journal.historyOfType(session.value(), "CompactionFailed", Seq.zero())).hasSize(1);
    }

    private static ProviderAdapter provider(List<ModelEvent> events) {
        return request -> events.stream();
    }

    private static Compaction compaction(InMemoryJournal journal, ProviderAdapter provider) {
        return new Compaction(journal, provider);
    }
}
