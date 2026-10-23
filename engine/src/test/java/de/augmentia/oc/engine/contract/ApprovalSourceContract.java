package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.model.ApprovalResult;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.human.ApprovalSource;
import de.augmentia.oc.engine.testing.ScriptedApproval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract of {@link ApprovalSource}. */
class ApprovalSourceContract {

    @Test
    void answersAreReturnedInOrder() {
        ApprovalSource source = new ScriptedApproval(new ApprovalResult.Approved(),
                new ApprovalResult.Denied(Option.some("no")));

        assertThat(source.request(reqId(), sessionId(), "shell", "git status")).isInstanceOf(ApprovalResult.Approved.class);
        ApprovalResult denied = source.request(reqId(), sessionId(), "edit", "a.txt");
        assertThat(((ApprovalResult.Denied) denied).feedback().orElse("")).isEqualTo("no");
    }

    @Test
    void actionsAreRecorded() {
        ScriptedApproval source = new ScriptedApproval(new ApprovalResult.Approved());
        source.request(reqId(), sessionId(), "read", "a.txt");
        assertThat(source.actions()).containsExactly("read");
    }

    private static de.augmentia.oc.engine.core.model.PermissionRequestId reqId() {
        return de.augmentia.oc.engine.core.model.PermissionRequestId.create();
    }
    private static SessionId sessionId() { return SessionId.create(); }

    @Test
    void missingAnswerIsAnError() {
        ApprovalSource source = new ScriptedApproval();
        assertThatThrownBy(() -> source.request(reqId(), sessionId(), "read", "a.txt")).isInstanceOf(IllegalStateException.class);
    }
}
