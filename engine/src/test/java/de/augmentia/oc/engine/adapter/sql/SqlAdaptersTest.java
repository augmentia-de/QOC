package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.testing.FakeFileSystem;
import de.augmentia.oc.engine.testing.FixedClock;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SqlAdaptersTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void journalStoresAndReadsEvents() {
        SqlJournal journal = new SqlJournal(dataSource, new FixedClock(Instant.EPOCH));
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
    void conversationStoresFoldedMessages() {
        SqlSessions sessions = new SqlSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        SqlConversation conversation = new SqlConversation(dataSource);
        Message message = new Message.UserMessage(MessageId.create(), "hello",
                List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH);
        conversation.insert(info.id(), Seq.of(1), message);

        assertThat(conversation.messages(info.id(), Seq.zero())).containsExactly(message);
        assertThat(conversation.message(info.id(), message.id()).orElseThrow()).isEqualTo(message);
        assertThat(conversation.message(info.id(), MessageId.create()).isEmpty()).isTrue();
    }

    @Test
    void sessionsStoreInfoAndInbox() {
        SqlSessions sessions = new SqlSessions(dataSource);
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
        SqlSessions sessions = new SqlSessions(dataSource);
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
        SqlSessions sessions = new SqlSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        sessions.putInbox(new InboxEntry(InboxId.create(), info.id(),
                new InboxItem.UserItem("text", List.of(), List.of(), List.of(), Map.of()),
                Delivery.QUEUE, Seq.of(1), Instant.EPOCH));

        SqlConversation conversation = new SqlConversation(dataSource);
        conversation.insert(info.id(), Seq.of(1),
                new Message.UserMessage(MessageId.create(), "hello",
                        List.of(), List.of(), List.of(), Map.of(), Instant.EPOCH));
        SqlInstructions instructions = new SqlInstructions(dataSource);
        instructions.putState(info.id(), new InstructionState(Seq.of(1), Seq.of(2),
                Map.of("a", "1"), Map.of("a", "2")));
        instructions.putEntry(info.id(), new InstructionEntry("a", "1", "h"));
        SqlClaims claims = new SqlClaims(dataSource);
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
        SqlJournal journal = new SqlJournal(dataSource, new FixedClock(Instant.EPOCH));
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
        SqlSessions sessions = new SqlSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        SqlClaims claims = new SqlClaims(dataSource);

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
        SqlApprovals approvals = new SqlApprovals(dataSource);
        ProjectId project = ProjectId.create();

        approvals.add(project, "read", "*.txt");
        approvals.add(project, "read", "*.txt");

        assertThat(approvals.list(project)).hasSize(1);
        approvals.remove(project, "read", "*.txt");
        assertThat(approvals.list(project)).isEmpty();
    }

    @Test
    void instructionStateEntriesAndBlobs() {
        SqlSessions sessions = new SqlSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);
        SqlInstructions instructions = new SqlInstructions(dataSource);

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
        SqlCredentials credentials = new SqlCredentials(dataSource);
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
        SqlToolOutput output = new SqlToolOutput(dataSource, Path.of("/tool-output"));
        SessionId session = SessionId.create();
        var artifact = output.save(session, "call", new byte[]{1, 2, 3});

        assertThat(artifact.uri()).startsWith("artifact://" + session.value() + "/art_");
        assertThat(output.find(session, artifact.id()).orElseThrow()).isEqualTo(artifact);
        assertThat(output.load(session, artifact.id())).containsExactly(1, 2, 3);
        assertThat(output.find(SessionId.create(), artifact.id()).isEmpty()).isTrue();
        SessionId fork = SessionId.create();
        var copied = output.copy(session, fork, artifact.id());
        assertThat(copied.session()).isEqualTo(fork);
        assertThat(output.load(fork, copied.id())).containsExactly(1, 2, 3);
        output.sweep(Instant.now().plusSeconds(1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> output.load(session, artifact.id()))
                .isInstanceOf(de.augmentia.oc.engine.core.error.StoreFailure.class);
    }

    @Test
    void snapshotsCaptureRestoreAndDiff() {
        FakeFileSystem fileSystem = new FakeFileSystem();
        Path file = Path.of("/work/a.txt");
        fileSystem.putText(file, "one");
        SqlSnapshots snapshots = new SqlSnapshots(dataSource, fileSystem);

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


    @Test
    void snapshotsCaptureMultipleFilesInOneBatch() {
        FakeFileSystem fileSystem = new FakeFileSystem();
        SqlSnapshots snapshots = new SqlSnapshots(dataSource, fileSystem);
        java.util.List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Path file = Path.of("/work/batch-" + i + ".txt");
            fileSystem.putText(file, "one-" + i);
            files.add(file);
        }

        SnapshotId before = snapshots.capture(files);
        for (Path file : files) fileSystem.putText(file, "two");
        SnapshotId after = snapshots.capture(files);

        assertThat(snapshots.diff(before, after)).hasSize(25);
        snapshots.restore(before, files);
        for (int i = 0; i < files.size(); i++) {
            assertThat(fileSystem.readText(files.get(i))).isEqualTo("one-" + i);
        }
    }

    @Test
    void toolResultCacheRoundTripsAndDeletes() {
        SqlToolResultCache cache = new SqlToolResultCache(dataSource);
        IdempotencyKey key = new IdempotencyKey("idem_sql_test");
        JsonValue value = JsonValue.of(Map.of(
                "type", JsonValue.of("ok"),
                "texts", JsonValue.of(List.of(JsonValue.of("hello")))));

        cache.store(key, "ses_1", "email_send", value);
        assertThat(cache.find(key)).isPresent();
        cache.deleteBySession("ses_1");
        assertThat(cache.find(key)).isEmpty();
    }

    @Test
    void sessionWorkspaceStoreRoundTrips() {
        SqlSessions sessions = new SqlSessions(dataSource);
        SessionInfo info = sessionInfo();
        sessions.put(info);

        sessions.put(info.id(), "ephemeral", "active", "/tmp/session", "inst-1", false);
        assertThat(sessions.executionMode(info.id())).isEqualTo("ephemeral");
        assertThat(sessions.state(info.id())).isEqualTo("active");
        assertThat(sessions.workspaceRoot(info.id())).isEqualTo("/tmp/session");
        assertThat(sessions.instanceId(info.id())).isEqualTo("inst-1");
        assertThat(sessions.retain(info.id())).isFalse();

        assertThat(sessions.ephemeralOrphans("other")).contains(info.id());
        sessions.setPausedPrompt(info.id(), "hello");
        assertThat(sessions.pausedPrompt(info.id())).isEqualTo("hello");
        sessions.updateState(info.id(), "suspended");
        assertThat(sessions.state(info.id())).isEqualTo("suspended");

        sessions.put(info.id(), "ephemeral", "active", "/tmp/session", "inst-1", true);
        assertThat(sessions.retain(info.id())).isTrue();
    }

    private static SessionInfo sessionInfo() {
        return new SessionInfo(SessionId.create(), ProjectId.create(),
                new Location(Path.of("/work"), ProjectId.create(), Option.none()), "title",
                AgentId.create(), Option.none(), List.of(new Rule("read", "*", Effect.ALLOW)),
                Money.zero(), TokenCount.zero(), Instant.EPOCH, Instant.EPOCH,
                Option.none(), Option.none(), 0, Option.none());
    }
}
