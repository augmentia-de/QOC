package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.Credential;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.store.CredentialStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL credential store; one active credential per integration. */
public final class SqliteCredentials implements CredentialStore {

    private final DataSource dataSource;

    public SqliteCredentials(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
    }

    @Override
    public List<Credential> saved(String integration) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from credential where integration = ? order by active desc, created_at desc")) {
            statement.setString(1, integration);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Credential> credentials = new ArrayList<>();
                while (resultSet.next()) credentials.add(read(resultSet));
                return List.copyOf(credentials);
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read credentials", e);
        }
    }

    @Override
    public Option<Credential> get(String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select * from credential where id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Option.some(read(resultSet)) : Option.none();
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read credential", e);
        }
    }

    @Override
    public Credential create(CredentialInput input) {
        return Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "update credential set active = false where integration = ? and active",
                    input.integration());
            String id = "cred_" + java.util.UUID.randomUUID();
            Instant created = Instant.now();
            Sqlite.execute(connection,
                    "insert into credential (id, integration, kind, secret, active, created_at) " +
                            "values (?, ?, ?, ?, true, ?)",
                    id, input.integration(), input.kind(), input.secret(),
                    java.sql.Timestamp.from(created));
            return new Credential(id, input.integration(), input.kind(),
                    Option.some(input.secret()), true, created);
        });
    }

    @Override
    public void activate(String id) {
        Sqlite.transaction(dataSource, connection -> {
            String integration;
            try (PreparedStatement statement = connection.prepareStatement(
                    "select integration from credential where id = ?")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) throw new StoreFailure("unknown credential: " + id);
                    integration = resultSet.getString(1);
                }
            } catch (java.sql.SQLException e) {
                throw new StoreFailure("cannot read credential", e);
            }
            Sqlite.execute(connection, "update credential set active = false where integration = ? and active",
                    integration);
            Sqlite.execute(connection, "update credential set active = true where id = ?", id);
            return null;
        });
    }

    @Override
    public void remove(String id) {
        Sqlite.transaction(dataSource, connection -> {
            Sqlite.execute(connection, "delete from credential where id = ?", id);
            return null;
        });
    }

    private static Credential read(ResultSet resultSet) throws java.sql.SQLException {
        return new Credential(resultSet.getString("id"), resultSet.getString("integration"),
                resultSet.getString("kind"), Option.of(resultSet.getString("secret")),
                resultSet.getBoolean("active"), Sqlite.instant(resultSet, "created_at"));
    }
}
