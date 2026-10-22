package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.PolicyStatement;
import de.augmentia.oc.engine.core.port.human.PolicySource;

import java.util.List;

/** Policy source with a fixed list of organization rules. */
public final class FixedPolicy implements PolicySource {

    private final List<PolicyStatement> statements;

    public FixedPolicy(List<PolicyStatement> statements) {
        this.statements = statements == null ? List.of() : List.copyOf(statements);
    }

    @Override
    public List<PolicyStatement> statements() {
        return statements;
    }
}
