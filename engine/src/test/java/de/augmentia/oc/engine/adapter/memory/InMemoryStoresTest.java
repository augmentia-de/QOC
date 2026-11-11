package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.Delivery;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.InboxEntry;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.InboxItem;
import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Location;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.SessionInfo;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.testing.FixedClock;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryStoresTest {

    private static RuntimeState state() {
        return new RuntimeState(new MemoryBudget(1 << 20));
    }

    private static SessionInfo sessionInfo(SessionId id) {
        return new SessionInfo(id, ProjectId.create(),
                new Location(Path.of("/work"), ProjectId.create(), Option.none()), "title",
                AgentId.create(), Option.none(), List.of(new Rule("read", "*", Effect.ALLOW)),
                Money.zero(), TokenCount.zero(), Instant.EPOCH, Instant.EPOCH,
                Option.none(), Option.none(), 0, Option.none());
    }

    private static Message userMessage(String text) {
        return new Message.UserMessage(MessageId.create(), text,
                List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH);
    }

    // --- MemoryStores / RuntimeState / MemoryBudget ---

    @Test
    void memoryStoresWiresAllPortsAndDefaults() {
        RuntimeState state = state();
        var stores = MemoryStores.create(state);

        assertThat(stores.sessions()).isSameAs(stores.sessionReader());
        assertThat(stores.workspaces()).isSameAs(stores.sessions());
        assertThat(stores.conversation()).isNotNull();
        assertThat(stores.journal()).isNotNull();
        assertThat(stores.claims()).isNotNull();
        assertThat(stores.approvals()).isNotNull();
        assertThat(stores.instructions()).isNotNull();
        assertThat(stores.credentials()).isNotNull();
        assertThat(stores.snapshots()).isNotNull();
        assertThat(stores.toolOutputs()).isNotNull();
        assertThat(stores.toolCache()).isNotNull();

        var defaulted = MemoryStores.create(null, null, null);
        assertThat(defaulted.sessions()).isNotNull();
    }

    @Test
    void runtimeStateDefaultsBudgetAndReturnsStableLocks() {
        RuntimeState state = new RuntimeState(null);
        SessionId id = SessionId.create();

        assertThat(state.budget).isNotNull();
        assertThat(state.lock(id)).isSameAs(state.lock(id));
    }

    @Test
    void memoryBudgetReservesReleasesAndRejectsOverflow() {
        MemoryBudget budget = new MemoryBudget(10);

        budget.reserve(0);
        budget.reserve(-5);
        budget.reserve(4);
        assertThat(budget.usedBytes()).isEqualTo(4);
        assertThat(budget.usageRatio()).isEqualTo(0.4);

        budget.release(0);
        budget.release(-1);
        budget.release(4);
        assertThat(budget.usedBytes()).isZero();

        assertThatThrownBy(() -> budget.reserve(11))
                .isInstanceOf(MemoryLimitExceeded.class)
                .hasMessageContaining("memory budget exceeded");

        assertThat(new MemoryBudget(0).maxBytes()).isEqualTo(1L << 30);
    }

    // --- InMemorySessionStore ---

    @Test
    void sessionStoreAndInboxRoundTrip() {
        InMemorySessionStore store = new InMemorySessionStore(state());
        SessionId id = SessionId.create();
        SessionInfo info = sessionInfo(id);

        assertThat(store.info(null).isEmpty()).isTrue();
        assertThat(store.info(id).isEmpty()).isTrue();
        store.put(info);
        assertThat(store.info(id).orElseThrow()).isEqualTo(info);
        assertThat(store.list()).containsExactly(info);

        InboxEntry entry = new InboxEntry(InboxId.create(), id,
                new InboxItem.UserItem("text", List.of(), List.of(), List.of(), Map.of()),
                Delivery.QUEUE, Seq.of(1), Instant.EPOCH);
        assertThat(store.inbox(null)).isEmpty();
        store.putInbox(entry);
        assertThat(store.inbox(id)).containsExactly(entry);
        assertThat(store.inboxItem(id, entry.id()).orElseThrow()).isEqualTo(entry);
        assertThat(store.inboxItem(null, null).isEmpty()).isTrue();
        assertThat(store.inboxItem(id, InboxId.create()).isEmpty()).isTrue();

        store.removeInbox(id, entry.id());
        store.removeInbox(null, null);
        assertThat(store.inbox(id)).isEmpty();

        assertThat(store.delete(id)).isTrue();
        assertThat(store.info(id).isEmpty()).isTrue();
    }

    @Test
    void sessionStoreRejectsNullArguments() {
        InMemorySessionStore store = new InMemorySessionStore(null);

        assertThatThrownBy(() -> store.put(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.setIdleOutcome(null, "idle")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.putInbox(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put(null, "workspace", "active", "/w", "i", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workspaceStoreRoundTripAndOrphanScan() {
        InMemorySessionStore store = new InMemorySessionStore(state());
        SessionId id = SessionId.create();

        assertThat(store.executionMode(id)).isEqualTo("workspace");
        assertThat(store.state(id)).isEqualTo("active");
        assertThat(store.workspaceRoot(id)).isNull();
        assertThat(store.retain(id)).isFalse();
        assertThat(store.pausedPrompt(id)).isNull();
        assertThat(store.instanceId(id)).isNull();
        store.setPausedPrompt(id, "ignored");
        store.updateState(id, "ignored");
        assertThat(store.ephemeralOrphans("inst-1")).isEmpty();

        store.put(id, "ephemeral", "active", "/tmp/session", "inst-1", true);
        assertThat(store.executionMode(id)).isEqualTo("ephemeral");
        assertThat(store.state(id)).isEqualTo("active");
        assertThat(store.workspaceRoot(id)).isEqualTo("/tmp/session");
        assertThat(store.instanceId(id)).isEqualTo("inst-1");
        assertThat(store.retain(id)).isTrue();
        assertThat(store.ephemeralOrphans("inst-1")).containsExactly(id);
        assertThat(store.ephemeralOrphans("inst-2")).isEmpty();

        store.setPausedPrompt(id, "hello");
        assertThat(store.pausedPrompt(id)).isEqualTo("hello");
        store.updateState(id, "cleanup_pending");
        assertThat(store.state(id)).isEqualTo("cleanup_pending");

        store.put(id, "ephemeral", "active", "/tmp/session", null, false);
        assertThat(store.ephemeralOrphans("any")).containsExactly(id);

        store.setIdleOutcome(id, "done");
        store.deleteWorkspace(id);
        assertThat(store.workspaceRoot(id)).isNull();
    }

    // --- InMemoryConversationStore ---

    @Test
    void conversationStoreTracksMessagesParentsAndSequences() {
        InMemoryConversationStore store = new InMemoryConversationStore(state());
        SessionId id = SessionId.create();

        assertThat(store.messages(id, null)).isEmpty();
        assertThat(store.messages(null, null)).isEmpty();
        assertThat(store.seqOf(id, MessageId.create()).isEmpty()).isTrue();
        assertThat(store.message(id, MessageId.create()).isEmpty()).isTrue();
        assertThat(store.parentOf(id, MessageId.create()).isEmpty()).isTrue();
        assertThat(store.nextSeq(id)).isEqualTo(Seq.of(1));

        Message first = userMessage("one");
        Message second = userMessage("two");
        store.insertWithParent(id, Seq.of(1), first, Option.none());
        store.insertWithParent(id, Seq.of(2), second, Option.some(first.id()));

        assertThat(store.messages(id, Seq.zero())).containsExactly(first, second);
        assertThat(store.messages(id, Seq.of(1))).containsExactly(second);
        assertThat(store.message(id, first.id()).orElseThrow()).isEqualTo(first);
        assertThat(store.seqOf(id, second.id()).orElseThrow()).isEqualTo(Seq.of(2));
        assertThat(store.parentOf(id, second.id()).orElseThrow()).isEqualTo(first.id());
        assertThat(store.parentOf(id, first.id()).isEmpty()).isTrue();

        // gap-filling insert
        Message fourth = userMessage("four");
        store.insertWithParent(id, Seq.of(4), fourth, Option.none());
        assertThat(store.messages(id, Seq.zero())).containsExactly(first, second, fourth);

        store.deleteFrom(id, Seq.of(2));
        assertThat(store.messages(id, Seq.zero())).containsExactly(first);
        store.deleteFrom(null, null);
        store.deleteFrom(id, Seq.of(0));

        assertThatThrownBy(() -> store.insertWithParent(null, Seq.of(1), first, Option.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.nextSeq(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void conversationStoreAcceptsNullRuntimeState() {
        InMemoryConversationStore store = new InMemoryConversationStore(null);
        SessionId id = SessionId.create();
        store.insertWithParent(id, Seq.of(1), userMessage("x"), Option.none());
        assertThat(store.messages(id, Seq.zero())).hasSize(1);
    }

    // --- InMemoryJournal ---

    @Test
    void journalAppendsReadsFiltersAndDeletes() {
        InMemoryJournal journal = new InMemoryJournal(state(), new FixedClock(Instant.EPOCH));
        SessionId id = SessionId.create();

        assertThat(journal.history(null, null)).isEmpty();
        assertThat(journal.history(" ", Seq.zero())).isEmpty();
        assertThat(journal.historyOfType(id.value(), null, Seq.zero())).isEmpty();
        assertThat(journal.appendAll(null)).isEmpty();

        Seq first = journal.append(new EventDraft(id.value(), 1,
                new SessionEvent.ExecutionStarted(id)));
        Seq second = journal.append(new EventDraft(id.value(), 1,
                new SessionEvent.ExecutionSucceeded(id)));
        assertThat(first).isEqualTo(Seq.of(1));
        assertThat(second).isEqualTo(Seq.of(2));

        assertThat(journal.history(id.value(), Seq.zero())).hasSize(2);
        assertThat(journal.history(id.value(), Seq.of(1))).hasSize(1);
        assertThat(journal.historyOfType(id.value(), "ExecutionSucceeded", Seq.zero())).hasSize(1);
        assertThat(journal.historyOfType(id.value(), "Unknown", Seq.zero())).isEmpty();

        journal.deleteOlderThan(null);
        journal.deleteOlderThan(Instant.EPOCH.plusSeconds(1));
        assertThat(journal.history(id.value(), Seq.zero())).isEmpty();

        journal.append(new EventDraft(id.value(), 1, new SessionEvent.ExecutionStarted(id)));
        journal.deleteAggregate(id.value());
        journal.deleteAggregate(" ");
        assertThat(journal.history(id.value(), Seq.zero())).isEmpty();

        assertThatThrownBy(() -> journal.append(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void journalAppendAllUsesGaplessSequence() {
        InMemoryJournal journal = new InMemoryJournal(null);
        SessionId id = SessionId.create();

        List<Seq> seqs = journal.appendAll(List.of(
                new EventDraft(id.value(), 1, new SessionEvent.ExecutionStarted(id)),
                new EventDraft(id.value(), 1, new SessionEvent.ExecutionSucceeded(id))));

        assertThat(seqs).containsExactly(Seq.of(1), Seq.of(2));
    }

    // --- InMemoryClaimStore ---

    @Test
    void claimsAreExclusiveAndReleasable() {
        InMemoryClaimStore store = new InMemoryClaimStore(state());
        SessionId id = SessionId.create();

        assertThat(store.open()).isEmpty();
        Claim claim = store.acquire(id, null).orElseThrow();
        assertThat(claim.claimedAt()).isEqualTo(Instant.EPOCH);
        assertThat(store.acquire(id, Instant.EPOCH).isEmpty()).isTrue();
        assertThat(store.open()).containsExactly(claim);

        store.release(id);
        store.release(null);
        assertThat(store.open()).isEmpty();
        assertThat(store.acquire(id, Instant.EPOCH).isPresent()).isTrue();

        assertThatThrownBy(() -> store.acquire(null, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- InMemoryApprovalStore ---

    @Test
    void approvalsAreListedPerProjectAndRemovable() {
        InMemoryApprovalStore store = new InMemoryApprovalStore(state());
        ProjectId project = ProjectId.create();

        assertThat(store.list(project)).isEmpty();
        store.add(project, "read", "*.txt");
        store.add(project, "write", "*.md");
        assertThat(store.list(project)).hasSize(2);
        assertThat(store.list(ProjectId.create())).isEmpty();

        store.remove(project, "read", "*.txt");
        assertThat(store.list(project))
                .extracting(a -> a.action())
                .containsExactly("write");
    }

    // --- InMemoryInstructionStore ---

    @Test
    void instructionStoreRoundTripsStateEntriesAndBlobs() {
        InMemoryInstructionStore store = new InMemoryInstructionStore(state());
        SessionId id = SessionId.create();

        assertThat(store.state(id).isEmpty()).isTrue();
        assertThat(store.entries(id)).isEmpty();
        assertThat(store.entries(null)).isEmpty();
        assertThat(store.state(null).isEmpty()).isTrue();

        InstructionState state = new InstructionState(Seq.of(1), Seq.of(2),
                Map.of("a", "1"), Map.of("a", "2"));
        store.putState(id, state);
        assertThat(store.state(id).orElseThrow()).isEqualTo(state);

        store.putEntry(id, new InstructionEntry("a", "one", "h1"));
        store.putEntry(id, new InstructionEntry("a", "two", "h2"));
        store.putEntry(id, new InstructionEntry("b", "three", "h3"));
        assertThat(store.entries(id)).extracting(InstructionEntry::value)
                .containsExactlyInAnyOrder("two", "three");

        store.removeEntry(id, "a");
        store.removeEntry(null, null);
        store.removeEntry(id, "missing");
        assertThat(store.entries(id)).extracting(InstructionEntry::key).containsExactly("b");

        assertThat(store.blob("h1").isEmpty()).isTrue();
        assertThat(store.blob(null).isEmpty()).isTrue();
        assertThat(store.blob(" ").isEmpty()).isTrue();
        store.putBlob("h1", "content");
        assertThat(store.blob("h1").orElseThrow()).isEqualTo("content");

        assertThatThrownBy(() -> store.putState(null, state)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.putEntry(id, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.putBlob(" ", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    // --- InMemoryCredentialStore ---

    @Test
    void credentialStoreCreatesFindsAndRemoves() {
        InMemoryCredentialStore store = new InMemoryCredentialStore(state());

        assertThat(store.saved(null)).isEmpty();
        assertThat(store.saved(" ")).isEmpty();
        assertThat(store.get(null).isEmpty()).isTrue();
        assertThat(store.get(" ").isEmpty()).isTrue();

        var created = store.create(new CredentialStore.CredentialInput("openai", "key", "secret"));
        assertThat(created.integration()).isEqualTo("openai");
        assertThat(created.active()).isFalse();
        assertThat(store.saved("openai")).containsExactly(created);
        assertThat(store.get(created.id()).orElseThrow()).isEqualTo(created);

        store.activate(created.id());
        store.activate("unknown");
        store.remove(created.id());
        store.remove(null);
        store.remove(" ");
        assertThat(store.saved("openai")).isEmpty();
        assertThat(store.get(created.id()).isEmpty()).isTrue();

        assertThatThrownBy(() -> store.create(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- InMemoryToolOutputStore ---

    @Test
    void toolOutputStoreSavesFindsAndLoads() {
        InMemoryToolOutputStore store = new InMemoryToolOutputStore(state());
        SessionId id = SessionId.create();

        assertThat(store.find(null, "id").isEmpty()).isTrue();
        assertThat(store.find(id, null).isEmpty()).isTrue();
        assertThat(store.find(id, " ").isEmpty()).isTrue();
        assertThat(store.load(null, "id")).isNull();
        assertThat(store.load(id, null)).isNull();
        store.sweep(null);
        store.sweep(Instant.now());

        var artifact = store.save(id, "call", new byte[]{1, 2, 3});
        assertThat(artifact.id()).startsWith("call_");
        assertThat(artifact.size()).isEqualTo(3);
        assertThat(store.find(id, artifact.id()).orElseThrow().id()).isEqualTo(artifact.id());
        assertThat(store.load(id, artifact.id())).containsExactly(1, 2, 3);
        assertThat(store.find(SessionId.create(), artifact.id()).isEmpty()).isTrue();

        var empty = store.save(id, null, null);
        assertThat(empty.id()).startsWith("out_");
        assertThat(empty.size()).isZero();
        assertThat(store.load(id, empty.id())).isEmpty();

        assertThatThrownBy(() -> store.save(null, "call", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- InMemoryToolResultCache ---

    @Test
    void toolResultCacheStoresAndDeletes() {
        InMemoryToolResultCache cache = new InMemoryToolResultCache(state());
        de.augmentia.oc.engine.core.model.IdempotencyKey key =
                new de.augmentia.oc.engine.core.model.IdempotencyKey("idem_test");

        assertThat(cache.find(null)).isEmpty();
        assertThat(cache.find(key)).isEmpty();
        cache.store(key, "s1", "tool", JsonValue.of("result"));
        assertThat(cache.find(key)).contains(JsonValue.of("result"));
        cache.delete(key);
        cache.delete(null);
        assertThat(cache.find(key)).isEmpty();

        cache.store(key, "s1", "tool", JsonValue.of("result"));
        cache.deleteBySession("s1");
        assertThat(cache.deleteCreatedBefore(Instant.now())).isZero();
        // the in-memory cache keeps entries without timestamps
        assertThat(cache.find(key)).isPresent();

        assertThatThrownBy(() -> cache.store(null, "s", "t", JsonValue.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
