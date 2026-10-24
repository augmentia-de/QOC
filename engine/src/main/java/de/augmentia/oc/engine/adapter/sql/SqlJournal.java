package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.event.EventCodec;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.Payload;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.StoredEvent;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.Journal;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL event log; sequences are gapless per aggregate. */
public final class SqlJournal implements Journal {

    private final DataSource dataSource;
    private final Clock clock;
    private final EventCodec codec;

    public SqlJournal(DataSource dataSource, Clock clock) {
        this(dataSource, clock, EventCodec.standard());
    }

    public SqlJournal(DataSource dataSource, Clock clock, EventCodec codec) {
        if (dataSource == null || clock == null || codec == null) {
            throw new IllegalArgumentException("journal dependencies must not be null");
        }
        this.dataSource = dataSource;
        this.clock = clock;
        this.codec = codec;
    }

    @Override
    public Seq append(EventDraft draft) {
        return Sql.transaction(dataSource, connection -> append(connection, draft));
    }

    @Override
    public List<Seq> appendAll(List<EventDraft> drafts) {
        if (drafts == null) throw new IllegalArgumentException("drafts must not be null");
        return Sql.transaction(dataSource, connection -> {
            List<Seq> sequences = new ArrayList<>(drafts.size());
            for (EventDraft draft : drafts) sequences.add(append(connection, draft));
            return List.copyOf(sequences);
        });
    }

    @Override
    public List<StoredEvent> history(String aggregateId, Seq after) {
        return query("select aggregate_id, seq, type, version, data, created_at from event " +
                "where aggregate_id = ? and seq > ? order by seq", aggregateId, after);
    }

    @Override
    public List<StoredEvent> historyOfType(String aggregateId, String type, Seq after) {
        return query("select aggregate_id, seq, type, version, data, created_at from event " +
                "where aggregate_id = ? and seq > ? and type = ? order by seq", aggregateId, after, type);
    }

    @Override
    public void deleteAggregate(String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId must not be blank");
        }
        Sql.transaction(dataSource, connection -> {
            Sql.execute(connection, "delete from event where aggregate_id = ?", aggregateId);
            Sql.execute(connection, "delete from event_sequence where aggregate_id = ?", aggregateId);
            return null;
        });
    }

    private Seq append(Connection connection, EventDraft draft) {
        long seq = nextSequence(connection, draft.aggregateId());
        String json = Sql.json(codec.encode(draft.payload()));
        Sql.execute(connection,
                "insert into event (aggregate_id, seq, type, version, data, created_at) " +
                        "values (?, ?, ?, ?, ?::jsonb, ?)",
                draft.aggregateId(), seq, draft.type(), draft.version(), json,
                java.sql.Timestamp.from(clock.now()));
        return Seq.of(seq);
    }

    private long nextSequence(Connection connection, String aggregateId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into event_sequence (aggregate_id, seq) values (?, 1) " +
                        "on conflict (aggregate_id) do update set seq = event_sequence.seq + 1 " +
                        "returning seq")) {
            statement.setString(1, aggregateId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot allocate event sequence", e);
        }
    }

    private List<StoredEvent> query(String sql, String aggregateId, Seq after, Object... extra) {
        Seq from = after == null ? Seq.zero() : after;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, aggregateId);
            statement.setLong(2, from.value());
            for (int i = 0; i < extra.length; i++) statement.setObject(3 + i, extra[i]);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<StoredEvent> events = new ArrayList<>();
                while (resultSet.next()) {
                    String type = resultSet.getString("type");
                    int version = resultSet.getInt("version");
                    JsonValue data = Sql.parse(resultSet.getString("data"));
                    Result<Payload> payload = codec.decode(type, version, data);
                    if (payload instanceof Result.Err<Payload> error) {
                        throw new StoreFailure("cannot decode event " + type + ": "
                                + error.error().message());
                    }
                    events.add(new StoredEvent(resultSet.getString("aggregate_id"),
                            Seq.of(resultSet.getLong("seq")), type, version,
                            payload.valueOrNull(), Sql.instant(resultSet, "created_at")));
                }
                return List.copyOf(events);
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read event history", e);
        }
    }
}
