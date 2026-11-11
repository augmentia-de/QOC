package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SavedApproval;
import de.augmentia.oc.engine.core.port.store.ApprovalStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-memory approval store: saved approvals per project in CopyOnWriteArraySet.
 */
public final class InMemoryApprovalStore implements ApprovalStore {

    private final RuntimeState state;

    public InMemoryApprovalStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    private Set<SavedApproval> set(ProjectId project) {
        return (Set<SavedApproval>) state.approvals.computeIfAbsent(project, k -> new CopyOnWriteArraySet<SavedApproval>());
    }

    @Override
    public void add(ProjectId project, String action, String resource) {
        set(project).add(new SavedApproval(de.augmentia.oc.engine.core.model.PermissionRequestId.create(), project, action, resource));
    }

    @Override
    public List<SavedApproval> list(ProjectId project) {
        Set<SavedApproval> s = (Set<SavedApproval>) state.approvals.get(project);
        if (s == null) return List.of();
        return List.copyOf(s);
    }

    @Override
    public void remove(ProjectId project, String action, String resource) {
        Set<SavedApproval> s = (Set<SavedApproval>) state.approvals.get(project);
        if (s != null) s.removeIf(a -> a.action().equals(action) && a.resource().equals(resource));
    }
}
