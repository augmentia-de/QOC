package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.adapter.sql.*;
import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.port.store.*;
import de.augmentia.oc.engine.testing.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Implementations under test: in-memory fake and PostgreSQL adapter. */
public final class Contracts {

    private Contracts() {}

    public static Stream<Journal> journals() {
        return Stream.of(
                new InMemoryJournal(),
                new SqlJournal(PostgresSupport.dataSource(), new SystemClock()));
    }

    public static Stream<ConversationFixture> conversations() {
        SessionId fakeSession = SessionId.create();
        SessionId sqlSession = SessionId.create();
        SqlSessions sqlSessions = new SqlSessions(PostgresSupport.dataSource());
        sqlSessions.put(sessionInfo(sqlSession));
        InMemoryConversation memory = new InMemoryConversation();
        SqlConversation sql = new SqlConversation(PostgresSupport.dataSource());
        return Stream.of(
                new ConversationFixture(memory, fakeSession, memory::add),
                new ConversationFixture(sql, sqlSession, sql::insert));
    }

    public static Stream<SessionFixture> sessionReaders() {
        InMemorySessions memory = new InMemorySessions();
        SessionId memoryId = SessionId.create();
        memory.put(sessionInfo(memoryId));

        SqlSessions sql = new SqlSessions(PostgresSupport.dataSource());
        SessionId sqlId = SessionId.create();
        sql.put(sessionInfo(sqlId));

        return Stream.of(
                new SessionFixture(memory, memoryId, memory::putInbox, memory::removeInbox),
                new SessionFixture(sql, sqlId, sql::putInbox, sql::removeInbox));
    }

    public static Stream<ClaimFixture> claims() {
        SqlSessions sessions = new SqlSessions(PostgresSupport.dataSource());
        SessionId sqlId = SessionId.create();
        sessions.put(sessionInfo(sqlId));
        return Stream.of(
                new ClaimFixture(new InMemoryClaims(), SessionId.create(), SessionId::create),
                new ClaimFixture(new SqlClaims(PostgresSupport.dataSource()), sqlId, () -> {
                    SessionId id = SessionId.create();
                    sessions.put(sessionInfo(id));
                    return id;
                }));
    }

    public static Stream<ApprovalStore> approvals() {
        return Stream.of(new InMemoryApprovals(), new SqlApprovals(PostgresSupport.dataSource()));
    }

    public static Stream<InstructionFixture> instructions() {
        SqlSessions sessions = new SqlSessions(PostgresSupport.dataSource());
        SessionId sqlId = SessionId.create();
        sessions.put(sessionInfo(sqlId));
        return Stream.of(
                new InstructionFixture(new InMemoryInstructions(), SessionId.create()),
                new InstructionFixture(new SqlInstructions(PostgresSupport.dataSource()), sqlId));
    }

    public static SessionInfo sessionInfo(SessionId id) {
        return new SessionInfo(id, ProjectId.create(),
                new Location(Path.of("/work"), ProjectId.create(), Option.none()), "title",
                AgentId.create(), Option.none(), List.of(), Money.zero(), TokenCount.zero(),
                Instant.EPOCH, Instant.EPOCH, Option.none(), Option.none(), 0, Option.none());
    }

    public record ConversationFixture(Conversation conversation, SessionId session, Writer writer) {}

    @FunctionalInterface
    public interface Writer {
        void insert(SessionId session, Seq seq, Message message);
    }

    public record SessionFixture(SessionReader reader, SessionId session,
                                 Consumer<InboxEntry> putInbox,
                                 BiConsumer<SessionId, InboxId> removeInbox) {}

    public record ClaimFixture(ClaimStore claims, SessionId session, java.util.function.Supplier<SessionId> newSession) {}

    public record InstructionFixture(InstructionStore store, SessionId session) {}
}
