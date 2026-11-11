package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SavedApproval;
import de.augmentia.oc.engine.core.port.store.ApprovalStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared JDBC implementation of {@link ApprovalStore}. PostgreSQL and SQLite
 * only provide their transaction semantics via {@link SqlDialect} (H9).
 */
public abstract class AbstractJdbcApprovals implements ApprovalStore {

    private final DataSource dataSource;
    private final SqlDialect dialect;

    protected AbstractJdbcApprovals(DataSource dataSource, SqlDialect dialect) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        if (dialect == null) throw new IllegalArgumentException("dialect must not be null");
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public void add(ProjectId project, String action, String resource) {
        dialect.transaction(dataSource, connection -> {
            Sql.execute(connection,
                    "insert into saved_approval (id, project_id, action, resource) " +
                            "values (?, ?, ?, ?) on conflict (project_id, action, resource) do nothing",
                    PermissionRequestId.create().value(), project.value(), action, resource);
            return null;
        });
    }

    @Override
    public List<SavedApproval> list(ProjectId project) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select id, project_id, action, resource from saved_approval where project_id = ?")) {
            statement.setString(1, project.value());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<SavedApproval> approvals = new ArrayList<>();
                while (resultSet.next()) {
                    approvals.add(new SavedApproval(
                            PermissionRequestId.parse(resultSet.getString("id")),
                            ProjectId.parse(resultSet.getString("project_id")),
                            resultSet.getString("action"),
                            resultSet.getString("resource")));
                }
                return List.copyOf(approvals);
            }
        } catch (SQLException e) {
            throw new StoreFailure("cannot read approvals", e);
        }
    }

    @Override
    public void remove(ProjectId project, String action, String resource) {
        dialect.transaction(dataSource, connection -> {
            Sql.execute(connection,
                    "delete from saved_approval where project_id = ? and action = ? and resource = ?",
                    project.value(), action, resource);
            return null;
        });
    }
}
