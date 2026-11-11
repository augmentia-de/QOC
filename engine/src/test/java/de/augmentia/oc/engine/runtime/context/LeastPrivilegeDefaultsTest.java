package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.eval.PermissionRules;
import de.augmentia.oc.engine.core.model.Effect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LeastPrivilegeDefaultsTest {

    @Test
    void destructiveShellCommandsAskInsteadOfBeingDenied() {
        var rules = ContextBuilder.leastPrivilegeDefaults();

        assertThat(PermissionRules.evaluate(rules, "shell", "rm -f /data/workspace/et"))
                .isEqualTo(Effect.ASK);
        assertThat(PermissionRules.evaluate(rules, "shell",
                "python3 -c \"import os; os.remove('et')\""))
                .isEqualTo(Effect.ASK);
    }

    @Test
    void readOnlyShellCommandsStayAllowed() {
        var rules = ContextBuilder.leastPrivilegeDefaults();

        assertThat(PermissionRules.evaluate(rules, "shell", "ls -la")).isEqualTo(Effect.ALLOW);
        assertThat(PermissionRules.evaluate(rules, "shell", "cat file.txt")).isEqualTo(Effect.ALLOW);
        assertThat(PermissionRules.evaluate(rules, "shell", "git status")).isEqualTo(Effect.ALLOW);
    }

    @Test
    void editRemainsDeniedByDefault() {
        assertThat(PermissionRules.evaluate(ContextBuilder.leastPrivilegeDefaults(),
                "edit", "src/Main.java")).isEqualTo(Effect.DENY);
    }
}
