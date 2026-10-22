package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.runtime.compaction.Compaction;
import de.augmentia.oc.engine.runtime.compaction.CompactionContext;
import de.augmentia.oc.engine.runtime.compaction.CompactionResult;
import de.augmentia.oc.engine.testing.InMemoryJournal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CompactionTest {

    @Test
    void compactionPublishesStartedAndEndedAndKeepsTheLog() {
        InMemoryJournal journal = new InMemoryJournal();
        Compaction compaction = new Compaction(journal, provider(List.of(
                new ModelEvent.TextStarted("b"),
                new ModelEvent.TextDelta("b", "sum"),
                new ModelEvent.TextEnded("b", "summary"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.of(1, 2), JsonValue.ofNull()))));
        SessionId session = SessionId.create();
        journal.append(new EventDraft(session.value(), 1, new SessionEvent.ExecutionStarted(session)));

        CompactionResult result = compaction.compact(context(session));

        assertThat(result).isInstanceOf(CompactionResult.Compacted.class);
        assertThat(((CompactionResult.Compacted) result).summary()).isEqualTo("summary");
        assertThat(journal.history(session.value(), Seq.zero())).hasSize(3);
        assertThat(journal.historyOfType(session.value(), "CompactionStarted", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(session.value(), "CompactionEnded", Seq.zero())).hasSize(1);
    }

    @Test
    void providerFailurePublishesCompactionFailed() {
        InMemoryJournal journal = new InMemoryJournal();
        Compaction compaction = new Compaction(journal, provider(List.of(
                new ModelEvent.ProviderFailed(new FailureKind.RateLimited(Option.none()), "slow",
                        Option.none(), Option.none()))));
        SessionId session = SessionId.create();

        CompactionResult result = compaction.compact(context(session));

        assertThat(result).isInstanceOf(CompactionResult.Failed.class);
        assertThat(journal.historyOfType(session.value(), "CompactionFailed", Seq.zero())).hasSize(1);
    }

    @Test
    void onlyOneCompactionRunsAtATime() throws Exception {
        InMemoryJournal journal = new InMemoryJournal();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProviderAdapter blocking = request -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            List<ModelEvent> events = List.of(new ModelEvent.Finished(
                    FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()));
            return events.stream();
        };
        Compaction compaction = new Compaction(journal, blocking);
        SessionId session = SessionId.create();

        Thread first = new Thread(() -> compaction.compact(context(session)));
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(compaction.compact(context(session))).isInstanceOf(CompactionResult.AlreadyInProgress.class);

        release.countDown();
        first.join(5_000);
        assertThat(first.isAlive()).isFalse();
    }

    @Test
    void epochStartIsReturnedUnchanged() {
        InMemoryJournal journal = new InMemoryJournal();
        Compaction compaction = new Compaction(journal, provider(List.of(
                new ModelEvent.TextEnded("b", "summary"),
                new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.zero(), JsonValue.ofNull()))));
        SessionId session = SessionId.create();

        CompactionResult.Compacted result = (CompactionResult.Compacted) compaction.compact(
                new CompactionContext(session, CompactionReason.OVERFLOW, request(), Option.none(),
                        Seq.of(42), Money.of(1), TokenCount.of(2, 3)));

        assertThat(result.epochStart()).isEqualTo(Seq.of(42));
    }

    private static CompactionContext context(SessionId session) {
        return new CompactionContext(session, CompactionReason.MANUAL, request(), Option.none(),
                Seq.of(1), Money.zero(), TokenCount.zero());
    }

    private static ModelRequest request() {
        return new ModelRequest(ModelRef.of(ProviderId.create(), ModelId.create()),
                List.of("summarize"), List.of(), List.of(), Option.none(),
                GenerationOptions.defaults(), Map.of(), Map.of(), Option.none());
    }

    private static ProviderAdapter provider(List<ModelEvent> events) {
        return request -> events.stream();
    }
}
