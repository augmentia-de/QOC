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
import java.util.ArrayList;
import java.util.List;

/** PostgreSQL stored approvals, unique per project/action/resource. */
public final class SqlApprovals implements ApprovalStore {

    private final DataSource dataSource;

    public SqlApprovals(DataSource dataSource) {
        if (dataSource == null) throw new IllegalArgumentException("dataSource must not be null");
        this.dataSource = dataSource;
    }

    @Override
    public void add(ProjectId project, String action, String resource) {
        Sql.transaction(dataSource, connection -> {
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
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read approvals", e);
        }
    }

    @Override
    public void remove(ProjectId project, String action, String resource) {
        Sql.transaction(dataSource, connection -> {
            Sql.execute(connection,
                    "delete from saved_approval where project_id = ? and action = ? and resource = ?",
                    project.value(), action, resource);
            return null;
        });
    }
}
