package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SavedApproval;
import de.augmentia.oc.engine.core.port.store.ApprovalStore;
import de.augmentia.oc.engine.testing.InMemoryApprovals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link ApprovalStore}. */
class ApprovalStoreContract {

    private InMemoryApprovals approvals;
    private ProjectId project;

    @BeforeEach
    void setUp() {
        approvals = new InMemoryApprovals();
        project = ProjectId.create();
    }

    @Test
    void addedApprovalIsListedForItsProject() {
        approvals.add(project, "shell", "git status *");
        assertThat(approvals.list(project))
                .extracting(SavedApproval::action, SavedApproval::resource)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("shell", "git status *"));
        assertThat(approvals.list(ProjectId.create())).isEmpty();
    }

    @Test
    void sameApprovalIsStoredOnlyOnce() {
        approvals.add(project, "read", "*");
        approvals.add(project, "read", "*");
        assertThat(approvals.list(project)).hasSize(1);
        assertThat(approvals.size()).isEqualTo(1);
    }

    @Test
    void removedApprovalDisappears() {
        approvals.add(project, "read", "*.txt");
        approvals.remove(project, "read", "*.txt");
        assertThat(approvals.list(project)).isEmpty();
    }

    @Test
    void differentResourcesAreDifferentApprovals() {
        approvals.add(project, "read", "*.txt");
        approvals.add(project, "read", "*.md");
        assertThat(approvals.list(project)).hasSize(2);
    }
}
