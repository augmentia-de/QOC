package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.InstructionEntry;
import de.augmentia.oc.engine.core.model.InstructionState;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.InstructionStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL instruction state, entries and blob store. */
public final class SqliteInstructions implements InstructionStore {

    private final DataSource dataSource;

    public SqliteInstructions(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
    }

    @Override
    public Option<InstructionState> state(SessionId session) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select epoch_start, through_seq, initial_values, current_values " +
                             "from instruction_state where session_id = ?")) {
            statement.setString(1, session.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Option.none();
                return Option.some(new InstructionState(
                        Seq.of(resultSet.getLong("epoch_start")),
                        Seq.of(resultSet.getLong("through_seq")),
                        Sqlite.stringMap(Sqlite.parse(resultSet.getString("initial_values"))),
                        Sqlite.stringMap(Sqlite.parse(resultSet.getString("current_values")))));
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read instruction state", e);
        }
    }

    @Override
    public void putState(SessionId session, InstructionState state) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection,
                    "insert into instruction_state (session_id, epoch_start, through_seq, initial_values, current_values) " +
                            "values (?, ?, ?, ?, ?) on conflict (session_id) do update set " +
                            "epoch_start = excluded.epoch_start, through_seq = excluded.through_seq, " +
                            "initial_values = excluded.initial_values, current_values = excluded.current_values",
                    session.value(), state.epochStart().value(), state.throughSeq().value(),
                    Sqlite.json(stringMapJson(state.initialValues())),
                    Sqlite.json(stringMapJson(state.currentValues())));
            return null;
        });
    }

    @Override
    public List<InstructionEntry> entries(SessionId session) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select key, value, hash from instruction_entry where session_id = ? order by key")) {
            statement.setString(1, session.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<InstructionEntry> entries = new ArrayList<>();
                while (resultSet.next()) {
                    entries.add(new InstructionEntry(resultSet.getString("key"),
                            resultSet.getString("value"), resultSet.getString("hash")));
                }
                return List.copyOf(entries);
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read instruction entries", e);
        }
    }

    @Override
    public void putEntry(SessionId session, InstructionEntry entry) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection,
                    "insert into instruction_entry (session_id, key, value, hash) values (?, ?, ?, ?) " +
                            "on conflict (session_id, key) do update set value = excluded.value, hash = excluded.hash",
                    session.value(), entry.key(), entry.value(), entry.hash());
            return null;
        });
    }

    @Override
    public void removeEntry(SessionId session, String key) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "delete from instruction_entry where session_id = ? and key = ?",
                    session.value(), key);
            return null;
        });
    }

    @Override
    public Option<String> blob(String hash) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select value from instruction_blob where hash = ?")) {
            statement.setString(1, hash);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Option.some(resultSet.getString("value")) : Option.none();
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read instruction blob", e);
        }
    }

    @Override
    public void putBlob(String hash, String value) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection,
                    "insert into instruction_blob (hash, value) values (?, ?) " +
                            "on conflict (hash) do update set value = excluded.value", hash, value);
            return null;
        });
    }

    private static de.augmentia.oc.engine.core.json.JsonValue stringMapJson(
            java.util.Map<String, String> values) {
        var map = new java.util.LinkedHashMap<String, de.augmentia.oc.engine.core.json.JsonValue>();
        for (var entry : values.entrySet()) {
            map.put(entry.getKey(), de.augmentia.oc.engine.core.json.JsonValue.of(entry.getValue()));
        }
        return de.augmentia.oc.engine.core.json.JsonValue.of(map);
    }
}
