package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SavedApproval;

import java.util.List;

/** No-op approval store for minimal (in-memory) mode: approvals are not persisted. */
public final class NoOpApprovalStore implements de.augmentia.oc.engine.core.port.store.ApprovalStore {
    @Override public void add(ProjectId project, String action, String resource) { /* no-op */ }
    @Override public List<SavedApproval> list(ProjectId project) { return List.of(); }
    @Override public void remove(ProjectId project, String action, String resource) { /* no-op */ }
}