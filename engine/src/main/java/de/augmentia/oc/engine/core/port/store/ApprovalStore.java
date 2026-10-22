package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SavedApproval;

import java.util.List;

/** Stored "always allow" approvals, always project-wide. */
public interface ApprovalStore {

    void add(ProjectId project, String action, String resource);

    List<SavedApproval> list(ProjectId project);

    void remove(ProjectId project, String action, String resource);
}
