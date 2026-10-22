package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;
import de.augmentia.oc.engine.testing.ScriptedApproval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract of {@link ApprovalSource}. */
class ApprovalSourceContract {

    @Test
    void answersAreReturnedInOrder() {
        ApprovalSource source = new ScriptedApproval(new ApprovalResult.Approved(),
                new ApprovalResult.Denied(Option.some("no")));

        assertThat(source.request("shell", "git status")).isInstanceOf(ApprovalResult.Approved.class);
        ApprovalResult denied = source.request("edit", "a.txt");
        assertThat(((ApprovalResult.Denied) denied).feedback().orElse("")).isEqualTo("no");
    }

    @Test
    void actionsAreRecorded() {
        ScriptedApproval source = new ScriptedApproval(new ApprovalResult.Approved());
        source.request("read", "a.txt");
        assertThat(source.actions()).containsExactly("read");
    }

    @Test
    void missingAnswerIsAnError() {
        ApprovalSource source = new ScriptedApproval();
        assertThatThrownBy(() -> source.request("read", "a.txt")).isInstanceOf(IllegalStateException.class);
    }
}
