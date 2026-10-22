package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SavedApproval;
import de.augmentia.oc.engine.core.port.store.ApprovalStore;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of ApprovalStore, run against the fake and PostgreSQL. */
class ApprovalStoreContract {

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#approvals")
    void addedApprovalIsListedForItsProject(ApprovalStore approvals) {
        ProjectId project = ProjectId.create();
        approvals.add(project, "shell", "git status *");

        assertThat(approvals.list(project))
                .extracting(SavedApproval::action, SavedApproval::resource)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("shell", "git status *"));
        assertThat(approvals.list(ProjectId.create())).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#approvals")
    void sameApprovalIsStoredOnlyOnce(ApprovalStore approvals) {
        ProjectId project = ProjectId.create();
        approvals.add(project, "read", "*");
        approvals.add(project, "read", "*");
        assertThat(approvals.list(project)).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#approvals")
    void removedApprovalDisappears(ApprovalStore approvals) {
        ProjectId project = ProjectId.create();
        approvals.add(project, "read", "*.txt");
        approvals.remove(project, "read", "*.txt");
        assertThat(approvals.list(project)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("de.augmentia.oc.engine.contract.Contracts#approvals")
    void differentResourcesAreDifferentApprovals(ApprovalStore approvals) {
        ProjectId project = ProjectId.create();
        approvals.add(project, "read", "*.txt");
        approvals.add(project, "read", "*.md");
        assertThat(approvals.list(project)).hasSize(2);
    }
}
