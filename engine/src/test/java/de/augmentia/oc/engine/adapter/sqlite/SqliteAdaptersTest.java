package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.testing.FakeFileSystem;
import de.augmentia.oc.engine.testing.FixedClock;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteAdaptersTest {

    private static DataSource dataSource;
    private static java.nio.file.Path dbPath;

    @BeforeAll
    static void migrate() throws Exception {
        dbPath = java.nio.file.Files.createTempFile("qoc", ".db");
        java.nio.file.Files.deleteIfExists(dbPath);
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + dbPath);
        dataSource = source;
        Sqlite.enableWal(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-sqlite").load().migrate();
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanup() throws Exception {
        if (dbPath != null) java.nio.file.Files.deleteIfExists(dbPath);
    }

    @Test
    void journalStoresAndReadsEvents() {
        SqliteJournal journal = new SqliteJournal(dataSource, new FixedClock(Instant.EPOCH));
        SessionId session = SessionId.create();

        Seq first = journal.append(new EventDraft(session.value(), 1,
                new SessionEvent.ExecutionStarted(session)));
        Seq second = journal.append(new EventDraft(session.value(), 1,
                new SessionEvent.ExecutionSucceeded(session)));

        assertThat(first).isEqualTo(Seq.of(1));
        assertThat(second).isEqualTo(Seq.of(2));
        assertThat(journal.history(session.value(), Seq.zero())).hasSize(2);
        assertThat(journal.historyOfType(session.value(), "ExecutionSucceeded", Seq.zero())).hasSize(1);
        assertThat(journal.history(session.value(), Seq.of(1))).hasSize(1);
    }

    @Test
    void journalAppendsAll() {
        SqliteJournal journal = new SqliteJournal(dataSource, new FixedClock(Instant.EPOCH));
        SessionId session = SessionId.create();
        List<Seq> seqs = journal.appendAll(List.of(
                new EventDraft(session.value(), 1, new SessionEvent.ExecutionStarted(session)),
                new EventDraft(session.value(), 1, new SessionEvent.ExecutionSucceeded(session))));
        assertThat(seqs).containsExactly(Seq.of(1), Seq.of(2));
        assertThat(journal.history(session.value(), Seq.zero())).hasSize(2);
    }

    @Test
    void enableWalRuns() {
        Sqlite.enableWal(dataSource);
    }

    @Test
    void conversationStoresFoldedMessages() {
        SqliteSessions sessions = new SqliteSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        SqliteConversation conversation = new SqliteConversation(dataSource);
        Message message = new Message.UserMessage(MessageId.create(), "hello",
                List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH);
        conversation.insert(info.id(), Seq.of(1), message);

        assertThat(conversation.messages(info.id(), Seq.zero())).containsExactly(message);
        assertThat(conversation.message(info.id(), message.id()).orElseThrow()).isEqualTo(message);
        assertThat(conversation.message(info.id(), MessageId.create()).isEmpty()).isTrue();
    }

    @Test
    void sessionsStoreInfoAndInbox() {
        SqliteSessions sessions = new SqliteSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);

        InboxEntry entry = new InboxEntry(InboxId.create(), info.id(),
                new InboxItem.UserItem("text", List.of(), List.of(), List.of(), Map.of()),
                Delivery.QUEUE, Seq.of(1), Instant.EPOCH);
        sessions.putInbox(entry);

        SessionInfo loaded = sessions.info(info.id()).orElseThrow();
        assertThat(loaded.id()).isEqualTo(info.id());
        assertThat(loaded.title()).isEqualTo(info.title());
        assertThat(loaded.permissions()).isEqualTo(info.permissions());
        assertThat(loaded.projectId()).isEqualTo(info.projectId());
        assertThat(sessions.inbox(info.id())).containsExactly(entry);
        assertThat(sessions.inboxItem(info.id(), entry.id()).orElseThrow()).isEqualTo(entry);

        sessions.removeInbox(info.id(), entry.id());
        assertThat(sessions.inbox(info.id())).isEmpty();
    }

    @Test
    void updatingSessionChangesTheAgent() {
        SqliteSessions sessions = new SqliteSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);

        AgentId replacement = AgentId.create();
        sessions.put(new SessionInfo(info.id(), info.projectId(), info.location(), info.title(),
                replacement, info.model(), info.permissions(), info.cost(), info.tokens(),
                info.created(), info.updated(), info.idle(), info.suspended(),
                info.resumeAttempts(), info.archived()));

        assertThat(sessions.info(info.id()).orElseThrow().agent()).isEqualTo(replacement);
    }

    @Test
    void deletingSessionRemovesDependentRows() {
        SqliteSessions sessions = new SqliteSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        sessions.putInbox(new InboxEntry(InboxId.create(), info.id(),
                new InboxItem.UserItem("text", List.of(), List.of(), List.of(), Map.of()),
                Delivery.QUEUE, Seq.of(1), Instant.EPOCH));

        SqliteConversation conversation = new SqliteConversation(dataSource);
        conversation.insert(info.id(), Seq.of(1),
                new Message.UserMessage(MessageId.create(), "hello",
                        List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        SqliteInstructions instructions = new SqliteInstructions(dataSource);
        instructions.putState(info.id(), new InstructionState(Seq.of(1), Seq.of(2),
                Map.of("a", "1"), Map.of("a", "2")));
        instructions.putEntry(info.id(), new InstructionEntry("a", "1", "h"));
        SqliteClaims claims = new SqliteClaims(dataSource);
        claims.acquire(info.id(), Instant.EPOCH);

        assertThat(sessions.delete(info.id())).isTrue();
        assertThat(sessions.info(info.id()).isEmpty()).isTrue();
        assertThat(sessions.inbox(info.id())).isEmpty();
        assertThat(conversation.messages(info.id(), Seq.zero())).isEmpty();
        assertThat(instructions.state(info.id()).isEmpty()).isTrue();
        assertThat(instructions.entries(info.id())).isEmpty();
        assertThat(claims.open()).noneMatch(claim -> claim.sessionId().equals(info.id()));
    }

    @Test
    void journalDeletesAggregateAndSequence() {
        SqliteJournal journal = new SqliteJournal(dataSource, new FixedClock(Instant.EPOCH));
        SessionId session = SessionId.create();
        journal.append(new EventDraft(session.value(), 1,
                new SessionEvent.ExecutionStarted(session)));

        journal.deleteAggregate(session.value());

        assertThat(journal.history(session.value(), Seq.zero())).isEmpty();
        Seq next = journal.append(new EventDraft(session.value(), 1,
                new SessionEvent.ExecutionSucceeded(session)));
        assertThat(next).isEqualTo(Seq.of(1));
    }

    @Test
    void claimsAreExclusiveAndCountAttempts() {
        SqliteSessions sessions = new SqliteSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        SqliteClaims claims = new SqliteClaims(dataSource);

        Claim first = claims.acquire(info.id(), Instant.EPOCH).orElseThrow();
        assertThat(first.resumeAttempts()).isZero();
        assertThat(claims.acquire(info.id(), Instant.EPOCH).isEmpty()).isTrue();
        claims.release(info.id());

        Claim second = claims.acquire(info.id(), Instant.EPOCH).orElseThrow();
        assertThat(second.resumeAttempts()).isEqualTo(1);
        assertThat(claims.open()).hasSize(1);
    }

    @Test
    void approvalsAreUniquePerProjectActionResource() {
        SqliteApprovals approvals = new SqliteApprovals(dataSource);
        ProjectId project = ProjectId.create();

        approvals.add(project, "read", "*.txt");
        approvals.add(project, "read", "*.txt");

        assertThat(approvals.list(project)).hasSize(1);
        approvals.remove(project, "read", "*.txt");
        assertThat(approvals.list(project)).isEmpty();
    }

    @Test
    void instructionStateEntriesAndBlobs() {
        SqliteSessions sessions = new SqliteSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        SqliteInstructions instructions = new SqliteInstructions(dataSource);

        InstructionState state = new InstructionState(Seq.of(1), Seq.of(2),
                Map.of("a", "1"), Map.of("a", "2"));
        instructions.putState(info.id(), state);
        assertThat(instructions.state(info.id()).orElseThrow()).isEqualTo(state);

        instructions.putEntry(info.id(), new InstructionEntry("a", "1", "h"));
        assertThat(instructions.entries(info.id())).hasSize(1);
        instructions.removeEntry(info.id(), "a");
        assertThat(instructions.entries(info.id())).isEmpty();

        instructions.putBlob("h", "text");
        assertThat(instructions.blob("h").orElseThrow()).isEqualTo("text");
    }

    @Test
    void credentialsHaveExactlyOneActiveConnection() {
        SqliteCredentials credentials = new SqliteCredentials(dataSource);
        Credential first = credentials.create(new CredentialStore.CredentialInput("openai", "key", "one"));
        Credential second = credentials.create(new CredentialStore.CredentialInput("openai", "key", "two"));

        assertThat(credentials.saved("openai")).hasSize(2);
        assertThat(credentials.saved("openai").stream().filter(Credential::active)).hasSize(1);
        credentials.activate(first.id());
        assertThat(credentials.get(first.id()).orElseThrow().active()).isTrue();
        assertThat(credentials.get(second.id()).orElseThrow().active()).isFalse();
    }

    @Test
    void toolOutputRoundTripsAndSweeps() {
        SqliteToolOutput output = new SqliteToolOutput(dataSource, Path.of("/tool-output"));
        Path path = output.save("call", new byte[]{1, 2, 3});

        assertThat(output.load(path)).containsExactly(1, 2, 3);
        output.sweep(Instant.now().plusSeconds(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> output.load(path))
                .isInstanceOf(de.augmentia.oc.engine.core.error.StoreFailure.class);
    }

    @Test
    void nextSeqIsMonotonic() {
        SqliteSessions sessions = new SqliteSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        SqliteConversation conversation = new SqliteConversation(dataSource);
        assertThat(conversation.nextSeq(info.id())).isEqualTo(Seq.of(1));
        conversation.insert(info.id(), conversation.nextSeq(info.id()),
                new Message.UserMessage(MessageId.create(), "hello", List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        assertThat(conversation.nextSeq(info.id())).isEqualTo(Seq.of(2));
    }

    @Test
    void snapshotsCaptureRestoreAndDiff() {
        FakeFileSystem fileSystem = new FakeFileSystem();
        Path file = Path.of("/work/a.txt");
        fileSystem.putText(file, "one");
        SqliteSnapshots snapshots = new SqliteSnapshots(dataSource, fileSystem);

        SnapshotId before = snapshots.capture(List.of(file));
        fileSystem.putText(file, "two");
        SnapshotId after = snapshots.capture(List.of(file));

        assertThat(snapshots.diff(before, after)).hasSize(1);
        assertThat(snapshots.diff(before, after).getFirst().kind()).isEqualTo(FileDiff.Kind.MODIFIED);

        snapshots.restore(before, List.of(file));
        assertThat(fileSystem.readText(file)).isEqualTo("one");

        snapshots.delete(null);
        snapshots.delete(after);
        assertThat(snapshots.diff(before, after))
                .extracting(FileDiff::kind)
                .contains(FileDiff.Kind.DELETED);
    }

    private static SessionInfo sessionInfo() {
        return new SessionInfo(SessionId.create(), ProjectId.create(),
                new Location(Path.of("/work"), ProjectId.create(), Option.none()), "title",
                AgentId.create(), Option.none(), List.of(new Rule("read", "*", Effect.ALLOW)),
                Money.zero(), TokenCount.zero(), Instant.EPOCH, Instant.EPOCH,
                Option.none(), Option.none(), 0, Option.none());
    }
}
