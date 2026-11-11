package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.oc.engine.adapter.sql.SqlDialect;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared JDBC implementation for the workflow specification tables.
 *
 * <p>Only specification data is stored here: the current definition and its
 * immutable snapshots. Run state is written to the engine journal.</p>
 */
public abstract class AbstractJdbcWorkflowStore implements WorkflowDefinitionStore {

    protected final DataSource dataSource;
    protected final SqlDialect dialect;

    protected AbstractJdbcWorkflowStore(DataSource dataSource, SqlDialect dialect) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        if (dialect == null) throw new IllegalArgumentException("dialect must not be null");
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public WorkflowDefinition get(String id) {
        return dialect.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select definition from workflow_definition where id = ?")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? WorkflowJson.read(resultSet.getString(1)) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot read workflow: " + id, e);
            }
        });
    }

    @Override
    public boolean delete(String id) {
        return dialect.transaction(dataSource, connection -> {
            try (PreparedStatement versions = connection.prepareStatement(
                    "delete from workflow_version where workflow_id = ?")) {
                versions.setString(1, id);
                versions.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot delete workflow versions: " + id, e);
            }
            try (PreparedStatement definition = connection.prepareStatement(
                    "delete from workflow_definition where id = ?")) {
                definition.setString(1, id);
                return definition.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot delete workflow: " + id, e);
            }
        });
    }

    @Override
    public List<String> list() {
        return dialect.transaction(dataSource, connection -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id from workflow_definition order by name asc, id asc");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) ids.add(resultSet.getString(1));
                return ids;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot list workflows", e);
            }
        });
    }

    @Override
    public WorkflowDefinition save(String id, WorkflowDefinition definition) {
        WorkflowDefinition normalized = WorkflowDefaults.withId(id, definition);
        String json = WorkflowJson.write(normalized);
        String hash = WorkflowJson.hash(json);
        return dialect.transaction(dataSource, connection -> {
            String previousJson = null;
            String previousHash = null;
            try (PreparedStatement statement = connection.prepareStatement(
                    "select definition, content_hash from workflow_definition where id = ?")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        previousJson = resultSet.getString(1);
                        previousHash = resultSet.getString(2);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot read previous workflow: " + id, e);
            }
            if (previousJson != null && !hash.equals(previousHash)) {
                insertVersion(connection, id, WorkflowJson.versionId(), previousJson, previousHash);
            }
            upsert(connection, normalized, json, hash);
            return normalized;
        });
    }

    @Override
    public WorkflowDefinition create(String name) {
        WorkflowDefinition definition = WorkflowDefaults.create(name);
        save(definition.id(), definition);
        return definition;
    }

    @Override
    public List<String> versions(String id) {
        return dialect.transaction(dataSource, connection -> {
            List<String> versions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "select version from workflow_version where workflow_id = ? "
                            + "order by created_at desc, version desc")) {
                statement.setString(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) versions.add(resultSet.getString(1));
                }
                return versions;
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot list versions: " + id, e);
            }
        });
    }

    @Override
    public WorkflowDefinition version(String id, String version) {
        return dialect.transaction(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select definition from workflow_version where workflow_id = ? and version = ?")) {
                statement.setString(1, id);
                statement.setString(2, version);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? WorkflowJson.read(resultSet.getString(1)) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot read version: " + id + "/" + version, e);
            }
        });
    }

    @Override
    public void snapshot(String id, String version, WorkflowDefinition definition) {
        WorkflowDefinition normalized = WorkflowDefaults.withId(id, definition);
        String json = WorkflowJson.write(normalized);
        String hash = WorkflowJson.hash(json);
        dialect.transaction(dataSource, connection -> {
            insertVersion(connection, id, version, json, hash);
            return null;
        });
    }

    @Override
    public WorkflowDefinition findByHash(String id, String contentHash) {
        return dialect.transaction(dataSource, connection -> {
            try (PreparedStatement current = connection.prepareStatement(
                    "select definition from workflow_definition where id = ? and content_hash = ?")) {
                current.setString(1, id);
                current.setString(2, contentHash);
                try (ResultSet resultSet = current.executeQuery()) {
                    if (resultSet.next()) return WorkflowJson.read(resultSet.getString(1));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot read workflow by hash: " + id, e);
            }
            try (PreparedStatement version = connection.prepareStatement(
                    "select definition from workflow_version where workflow_id = ? and content_hash = ? "
                            + "order by created_at desc, version desc")) {
                version.setString(1, id);
                version.setString(2, contentHash);
                version.setMaxRows(1);
                try (ResultSet resultSet = version.executeQuery()) {
                    return resultSet.next() ? WorkflowJson.read(resultSet.getString(1)) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot read workflow version by hash: " + id, e);
            }
        });
    }

    private void upsert(Connection connection, WorkflowDefinition definition,
                        String json, String hash) {
        String now = dialect.nowExpression();
        String sql = "insert into workflow_definition "
                + "(id, name, version, status, definition, content_hash, created_at, updated_at) "
                + "values (?, ?, ?, ?, " + dialect.jsonParameter() + ", ?, " + now + ", " + now + ") "
                + "on conflict (id) do update set "
                + "name = excluded.name, version = excluded.version, status = excluded.status, "
                + "definition = excluded.definition, content_hash = excluded.content_hash, "
                + "updated_at = " + now;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, definition.id());
            statement.setString(2, definition.name());
            statement.setString(3, definition.version());
            statement.setString(4, definition.status());
            statement.setString(5, json);
            statement.setString(6, hash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save workflow: " + definition.id(), e);
        }
    }

    private void insertVersion(Connection connection, String id, String version,
                               String json, String hash) {
        String sql = "insert into workflow_version "
                + "(workflow_id, version, definition, content_hash, created_at) "
                + "values (?, ?, " + dialect.jsonParameter() + ", ?, " + dialect.nowExpression() + ") "
                + "on conflict (workflow_id, version) do update set "
                + "definition = excluded.definition, content_hash = excluded.content_hash";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, version);
            statement.setString(3, json);
            statement.setString(4, hash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write workflow version: " + id + "/" + version, e);
        }
    }
}
