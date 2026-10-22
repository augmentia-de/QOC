package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.PolicyStatement;
import de.augmentia.oc.engine.core.port.human.PolicySource;

import java.util.List;

/** Policy source without organization rules. */
public final class EmptyPolicy implements PolicySource {

    @Override
    public List<PolicyStatement> statements() {
        return List.of();
    }
}
