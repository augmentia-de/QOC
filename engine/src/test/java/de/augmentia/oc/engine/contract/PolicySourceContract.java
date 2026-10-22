package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.PolicyStatement;
import de.augmentia.oc.engine.core.port.human.PolicySource;
import de.augmentia.oc.engine.testing.EmptyPolicy;
import de.augmentia.oc.engine.testing.FixedPolicy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link PolicySource}. */
class PolicySourceContract {

    @Test
    void emptyPolicyHasNoStatements() {
        assertThat(new EmptyPolicy().statements()).isEmpty();
    }

    @Test
    void fixedPolicyReturnsItsStatementsImmutably() {
        PolicyStatement statement = PolicyStatement.deny("provider.use", "prv_1");
        PolicySource source = new FixedPolicy(List.of(statement));

        assertThat(source.statements()).containsExactly(statement);
    }

    @Test
    void nullListBecomesEmpty() {
        assertThat(new FixedPolicy(null).statements()).isEmpty();
    }
}
