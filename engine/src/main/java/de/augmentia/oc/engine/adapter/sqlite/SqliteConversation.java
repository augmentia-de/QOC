package de.augmentia.oc.engine.adapter.sqlite;

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
public final class SqliteConversation implements ConversationStore {

    private final DataSource dataSource;

    public SqliteConversation(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
    }

    /** Projection helper: writes one folded message at a sequence boundary. */
    public void insert(SessionId session, Seq seq, Message message) {
        if (session == null || seq == null || message == null) {
            throw new IllegalArgumentException("message fields must not be null");
        }
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection,
                    "insert into session_message (id, session_id, seq, type, data, created_at) " +
                            "values (?, ?, ?, ?, ?, datetime('now'))",
                    message.id().value(), session.value(), seq.value(),
                    message.getClass().getSimpleName(),
                    Sqlite.json(ContractCodec.encode(message, Message.class)));
            return null;
        });
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

    private static Message decode(String json) {
        return ContractCodec.decode(Sqlite.parse(json), Message.class);
    }
}
