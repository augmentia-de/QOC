package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SavedApproval;
import de.augmentia.oc.engine.core.port.store.ApprovalStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory stored approvals, unique per (project, action, resource). */
public final class InMemoryApprovals implements ApprovalStore {

    private final Map<Key, SavedApproval> approvals = new LinkedHashMap<>();

    @Override
    public synchronized void add(ProjectId project, String action, String resource) {
        Key key = new Key(project, action, resource);
        approvals.computeIfAbsent(key, ignored -> new SavedApproval(
                PermissionRequestId.create(), project, action, resource));
    }

    @Override
    public synchronized List<SavedApproval> list(ProjectId project) {
        return approvals.values().stream()
                .filter(approval -> approval.projectId().equals(project))
                .toList();
    }

    @Override
    public synchronized void remove(ProjectId project, String action, String resource) {
        approvals.remove(new Key(project, action, resource));
    }

    public synchronized int size() {
        return approvals.size();
    }

    private record Key(ProjectId project, String action, String resource) {}
}
