package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
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
        String key = instructions.state(session).orElseThrow()
                .currentValues().get(CompactionService.SUMMARY_KEY);
        assertThat(key).matches("[0-9a-f]{64}");
        assertThat(instructions.blob(key).orElseThrow()).isEqualTo("summary text");
    }

    @Test
    void shortConversationHasNoManualWork() {
        conversation.add(session, Seq.of(1),
                new Message.UserMessage(MessageId.create(), "only message",
                        List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        Compaction compaction = compaction(journal, provider(List.of(
                new ModelEvent.TextEnded("b", "unused"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))));
        CompactionService service = new CompactionService(journal, conversation, instructions,
                compaction, new FixedClock(Instant.EPOCH));

        assertThat(service.hasWork(session)).isFalse();
        assertThat(instructions.state(session).isPresent()).isFalse();
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

    @Test
    void secondCompactionSeesThePreviousSummaryText() {
        for (int i = 0; i < 10; i++) {
            conversation.add(session, Seq.of(i + 1),
                    new Message.UserMessage(MessageId.create(), "msg " + i,
                            List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        }
        List<ModelRequest> requests = new ArrayList<>();
        String template = "## Objective\n- work\n## Requirements\n- none\n"
                + "## Decisions\n- none\n## Work State\n- active\n## Next Move\n1. continue";
        ProviderAdapter provider = request -> {
            requests.add(request);
            return List.<ModelEvent>of(
                    new ModelEvent.TextEnded("b", template),
                    new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))
                    .stream();
        };
        CompactionService service = new CompactionService(journal, conversation, instructions,
                compaction(journal, provider), new FixedClock(Instant.EPOCH), provider);

        service.compact(session, CompactionReason.MANUAL, model, Option.none(), Seq.of(10), Instant.EPOCH);
        // Replace the stored text with a recognizable predecessor so the
        // second request proves that the text, not the hash map, is used.
        String firstKey = instructions.state(session).orElseThrow()
                .currentValues().get(CompactionService.SUMMARY_KEY);
        instructions.putBlob(firstKey, "FIRST-SUMMARY-TEXT");
        service.compact(session, CompactionReason.MANUAL, model, Option.none(), Seq.of(10), Instant.EPOCH);

        assertThat(requests).hasSize(2);
        String secondBody = ((ModelContent.Text) ((ModelMessage.User) requests.get(1)
                .messages().getFirst()).content().getFirst()).text();
        assertThat(secondBody).contains("FIRST-SUMMARY-TEXT");
        assertThat(secondBody).doesNotContain(CompactionService.SUMMARY_KEY);
    }

    @Test
    void missingPredecessorBlobIsTypedAndDoesNotCallTheProvider() {
        instructions.putState(session, new InstructionState(Seq.of(4), Seq.of(4),
                Map.of(), Map.of(CompactionService.SUMMARY_KEY, "missing-key")));
        ProviderAdapter provider = request -> {
            throw new AssertionError("provider must not be called");
        };
        CompactionService service = new CompactionService(journal, conversation, instructions,
                compaction(journal, provider), new FixedClock(Instant.EPOCH), provider);

        CompactionResult result = service.compact(session, CompactionReason.MANUAL, model,
                Option.none(), Seq.of(5), Instant.EPOCH);

        assertThat(result).isInstanceOf(CompactionResult.PredecessorMissing.class);
        assertThat(((CompactionResult.PredecessorMissing) result).message())
                .contains("missing-key");
    }

    private static ProviderAdapter provider(List<ModelEvent> events) {
        return request -> events.stream();
    }

    private static Compaction compaction(InMemoryJournal journal, ProviderAdapter provider) {
        return new Compaction(journal, provider);
    }
}
