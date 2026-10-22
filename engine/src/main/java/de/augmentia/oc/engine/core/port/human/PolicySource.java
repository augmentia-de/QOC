package de.augmentia.oc.engine.core.port.human;

import de.augmentia.oc.engine.core.model.PolicyStatement;

import java.util.List;

/** Organization policy source. Policy rules are applied last. */
public interface PolicySource {

    List<PolicyStatement> statements();
}
