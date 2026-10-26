package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.event.ContractCodec;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.Conversation;
import de.augmentia.oc.engine.core.port.store.ConversationStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL projection of the folded conversation. */
public final class SqlConversation implements ConversationStore {

    private final DataSource dataSource;

    public SqlConversation(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
    }

    /** Projection helper: writes one folded message at a sequence boundary. */
    @Override
    public void insertWithParent(SessionId session, Seq seq, Message message, Option<MessageId> parent) {
        if (session == null || seq == null || message == null) {
            throw new IllegalArgumentException("message fields must not be null");
        }
        Option<MessageId> requested = parent == null ? Option.none() : parent;
        Sql.transaction(dataSource, connection -> {
            Option<MessageId> effective = requested.isEmpty()
                    ? previousId(connection, session) : requested;
            Sql.execute(connection,
                    "insert into session_message (id, session_id, seq, type, data, parent_id, created_at) " +
                            "values (?, ?, ?, ?, ?::jsonb, ?, now())",
                    message.id().value(), session.value(), seq.value(),
                    message.getClass().getSimpleName(),
                    Sql.json(ContractCodec.encode(message, Message.class)),
                    effective.map(MessageId::value).orElse(null));
            return null;
        });
    }

    private static Option<MessageId> previousId(Connection connection, SessionId session) {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from session_message where session_id = ? order by seq desc limit 1")) {
            statement.setString(1, session.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Option.some(MessageId.parse(resultSet.getString("id")))
                        : Option.none();
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read previous message", e);
        }
    }


    @Override
    public Seq nextSeq(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select coalesce(max(seq), 0) + 1 from session_message where session_id = ?")) {
            statement.setString(1, session.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return Seq.of(resultSet.getLong(1));
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot allocate conversation sequence", e);
        }
    }

    @Override
    public Option<Seq> seqOf(SessionId session, MessageId message) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select seq from session_message where session_id = ? and id = ?")) {
            statement.setString(1, session.value());
            statement.setString(2, message.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Option.none();
                return Option.some(Seq.of(resultSet.getLong(1)));
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read message sequence", e);
        }
    }

    @Override
    public void deleteFrom(SessionId session, Seq seq) {
        Sql.transaction(dataSource, connection -> {
            Sql.execute(connection,
                    "delete from session_message where session_id = ? and seq >= ?",
                    session.value(), seq.value());
            return null;
        });
    }

    @Override
    public List<Message> messages(SessionId session, Seq afterSeq) {
        Seq from = afterSeq == null ? Seq.zero() : afterSeq;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select data from session_message where session_id = ? and seq > ? order by seq")) {
            statement.setString(1, session.value());
            statement.setLong(2, from.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Message> messages = new ArrayList<>();
                while (resultSet.next()) {
                    messages.add(decode(resultSet.getString("data")));
                }
                return List.copyOf(messages);
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read conversation", e);
        }
    }

    @Override
    public Option<Message> message(SessionId session, MessageId id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select data from session_message where session_id = ? and id = ?")) {
            statement.setString(1, session.value());
            statement.setString(2, id.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Option.none();
                return Option.some(decode(resultSet.getString("data")));
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read message", e);
        }
    }

    @Override
    public Option<MessageId> parentOf(SessionId session, MessageId id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select parent_id from session_message where session_id = ? and id = ?")) {
            statement.setString(1, session.value());
            statement.setString(2, id.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Option.none();
                return Option.of(resultSet.getString("parent_id"))
                        .map(MessageId::parse);
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read message parent", e);
        }
    }

    private static Message decode(String json) {
        return ContractCodec.decode(Sql.parse(json), Message.class);
    }
}
