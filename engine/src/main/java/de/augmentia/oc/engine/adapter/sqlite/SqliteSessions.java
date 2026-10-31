package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.event.ContractCodec;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.*;
import de.augmentia.oc.engine.core.port.store.SessionReader;
import de.augmentia.oc.engine.core.port.store.SessionStore;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL session read model and inbox. */
public final class SqliteSessions implements SessionStore, de.augmentia.oc.engine.core.port.store.SessionWorkspaceStore {

    private final DataSource dataSource;

    public SqliteSessions(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
    }

    public void put(SessionInfo info) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection,
                    "insert into session (id, project_id, directory, workspace_id, title, agent_id, " +
                            "model, permissions, cost, tokens, time_created, time_updated, time_idle, " +
                            "time_suspended, resume_attempts, time_archived) " +
                            "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                            "on conflict (id) do update set title = excluded.title, " +
                            "agent_id = excluded.agent_id, " +
                            "model = excluded.model, permissions = excluded.permissions, " +
                            "cost = excluded.cost, tokens = excluded.tokens, " +
                            "time_updated = excluded.time_updated, time_idle = excluded.time_idle, " +
                            "time_suspended = excluded.time_suspended, " +
                            "resume_attempts = excluded.resume_attempts, time_archived = excluded.time_archived",
                    info.id().value(), info.projectId().value(), info.location().directory().toString(),
                    info.location().workspaceId().orElse(null), info.title(), info.agent().value(),
                    info.model().map(model -> Sqlite.json(ContractCodec.encode(model))).orElse(null),
                    permissionsJson(info.permissions()),
                    info.cost().amount(),
                    Sqlite.json(ContractCodec.encode(info.tokens())),
                    java.sql.Timestamp.from(info.created()), java.sql.Timestamp.from(info.updated()),
                    info.idle().map(java.sql.Timestamp::from).orElse(null),
                    info.suspended().map(java.sql.Timestamp::from).orElse(null),
                    info.resumeAttempts(),
                    info.archived().map(java.sql.Timestamp::from).orElse(null));
            return null;
        });
    }

    public void putInbox(InboxEntry entry) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection,
                    "insert into session_inbox (id, session_id, type, payload, delivery, enqueued_seq, created_at) " +
                            "values (?, ?, ?, ?, ?, ?, ?) " +
                            "on conflict (id) do update set payload = excluded.payload, " +
                            "delivery = excluded.delivery, enqueued_seq = excluded.enqueued_seq",
                    entry.id().value(), entry.sessionId().value(),
                    entry.item().getClass().getSimpleName(),
                    Sqlite.json(ContractCodec.encode(entry.item(), InboxItem.class)),
                    entry.delivery().name().toLowerCase(java.util.Locale.ROOT), entry.enqueuedSeq().value(),
                    java.sql.Timestamp.from(entry.created()));
            return null;
        });
    }

    public void removeInbox(SessionId session, InboxId id) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "delete from session_inbox where session_id = ? and id = ?",
                    session.value(), id.value());
            return null;
        });
    }

    @Override
    public boolean delete(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "delete from session_claim where session_id = ?", session.value());
            Sqlite.execute(connection, "delete from resume_counter where session_id = ?", session.value());
            Sqlite.execute(connection, "delete from session_message where session_id = ?", session.value());
            Sqlite.execute(connection, "delete from session_inbox where session_id = ?", session.value());
            Sqlite.execute(connection, "delete from instruction_entry where session_id = ?", session.value());
            Sqlite.execute(connection, "delete from instruction_state where session_id = ?", session.value());
            Sqlite.execute(connection, "delete from session where id = ?", session.value());
            return null;
        });
        return true;
    }

    @Override
    public void put(SessionId id, String executionMode, String state, String workspaceRoot, String instanceId, boolean retain) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection,
                    "update session set execution_mode = ?, state = ?, workspace_root = ?, " +
                            "instance_id = ?, retain = ? where id = ?",
                    executionMode == null ? "workspace" : executionMode,
                    state == null ? "active" : state, workspaceRoot, instanceId, retain, id.value());
            return null;
        });
    }

    @Override
    public boolean retain(SessionId id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select retain from session where id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return false;
                return resultSet.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new StoreFailure("cannot read retain", e);
        }
    }

    @Override
    public String executionMode(SessionId id) {
        return scalar(id, "execution_mode", "workspace");
    }

    @Override
    public String state(SessionId id) {
        return scalar(id, "state", "active");
    }

    @Override
    public String workspaceRoot(SessionId id) {
        return scalar(id, "workspace_root", null);
    }

    @Override
    public void setPausedPrompt(SessionId id, String prompt) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "update session set paused_prompt = ? where id = ?", prompt, id.value());
            return null;
        });
    }

    @Override
    public String pausedPrompt(SessionId id) {
        return scalar(id, "paused_prompt", null);
    }

    @Override
    public String instanceId(SessionId id) {
        return scalar(id, "instance_id", null);
    }

    @Override
    public void updateState(SessionId id, String state) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "update session set state = ? where id = ?",
                    state == null ? "active" : state, id.value());
            return null;
        });
    }

    @Override
    public java.util.List<SessionId> ephemeralOrphans(String instanceId) {
        java.util.List<SessionId> result = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id from session where execution_mode = 'ephemeral' " +
                             "and state in ('active','cleanup_pending') " +
                             "and (instance_id is null or instance_id <> ?)")) {
            statement.setString(1, instanceId == null ? "" : instanceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) result.add(SessionId.parse(resultSet.getString("id")));
            }
        } catch (SQLException e) {
            throw new StoreFailure("cannot list ephemeral sessions", e);
        }
        return java.util.List.copyOf(result);
    }

    @Override
    public void deleteWorkspace(SessionId id) {
        // no-op: SessionStore.delete already removes the session row
    }

    private String scalar(SessionId id, String column, String fallback) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select " + column + " from session where id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return fallback;
                String v = resultSet.getString(1);
                return v == null ? fallback : v;
            }
        } catch (SQLException e) {
            throw new StoreFailure("cannot read " + column, e);
        }
    }

    public List<SessionInfo> list() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from session order by time_created")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SessionInfo> sessions = new ArrayList<>();
                while (resultSet.next()) sessions.add(readInfo(resultSet));
                return List.copyOf(sessions);
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot list sessions", e);
        }
    }

    @Override
    public Option<SessionInfo> info(SessionId session) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from session where id = ?")) {
            statement.setString(1, session.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Option.none();
                return Option.some(readInfo(resultSet));
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read session", e);
        }
    }

    @Override
    public List<InboxEntry> inbox(SessionId session) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from session_inbox where session_id = ? order by enqueued_seq")) {
            statement.setString(1, session.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<InboxEntry> entries = new ArrayList<>();
                while (resultSet.next()) entries.add(readInbox(resultSet));
                return List.copyOf(entries);
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read inbox", e);
        }
    }

    @Override
    public Option<InboxEntry> inboxItem(SessionId session, InboxId id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from session_inbox where session_id = ? and id = ?")) {
            statement.setString(1, session.value());
            statement.setString(2, id.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Option.none();
                return Option.some(readInbox(resultSet));
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read inbox item", e);
        }
    }

    private SessionInfo readInfo(ResultSet resultSet) throws java.sql.SQLException {
        String modelJson = resultSet.getString("model");
        Option<ModelRef> model = modelJson == null
                ? Option.none()
                : Option.some(ContractCodec.decode(Sqlite.parse(modelJson), ModelRef.class));
        BigDecimal cost = resultSet.getBigDecimal("cost");
        TokenCount tokens = ContractCodec.decode(Sqlite.parse(resultSet.getString("tokens")), TokenCount.class);
        Location location = new Location(java.nio.file.Path.of(resultSet.getString("directory")),
                ProjectId.parse(resultSet.getString("project_id")),
                Option.of(resultSet.getString("workspace_id")));
        return new SessionInfo(
                SessionId.parse(resultSet.getString("id")),
                ProjectId.parse(resultSet.getString("project_id")),
                location,
                resultSet.getString("title"),
                AgentId.parse(resultSet.getString("agent_id")),
                model,
                readRules(resultSet.getString("permissions")),
                Money.of(cost),
                tokens,
                Sqlite.instant(resultSet, "time_created"),
                Sqlite.instant(resultSet, "time_updated"),
                Option.of(Sqlite.instant(resultSet, "time_idle")),
                Option.of(Sqlite.instant(resultSet, "time_suspended")),
                resultSet.getInt("resume_attempts"),
                Option.of(Sqlite.instant(resultSet, "time_archived")));
    }

    private static InboxEntry readInbox(ResultSet resultSet) throws java.sql.SQLException {
        InboxItem item = ContractCodec.decode(Sqlite.parse(resultSet.getString("payload")), InboxItem.class);
        return new InboxEntry(
                InboxId.parse(resultSet.getString("id")),
                SessionId.parse(resultSet.getString("session_id")),
                item,
                Delivery.valueOf(resultSet.getString("delivery").toUpperCase(java.util.Locale.ROOT)),
                Seq.of(resultSet.getLong("enqueued_seq")),
                Sqlite.instant(resultSet, "created_at"));
    }

    private static List<Rule> readRules(String json) {
        JsonValue value = Sqlite.parse(json);
        if (!(value instanceof JsonValue.Arr array)) return List.of();
        List<Rule> rules = new ArrayList<>();
        for (JsonValue item : array.values()) rules.add(ContractCodec.decode(item, Rule.class));
        return List.copyOf(rules);
    }

    private static String permissionsJson(List<Rule> rules) {
        return Sqlite.json(JsonValue.of(rules.stream()
                .map(rule -> ContractCodec.encode(rule))
                .toList()));
    }
}
