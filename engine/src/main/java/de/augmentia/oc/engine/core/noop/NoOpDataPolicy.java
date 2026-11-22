package de.augmentia.oc.engine.core.noop;

import de.augmentia.oc.engine.core.contract.permission.DataPolicy;
import de.augmentia.oc.engine.core.contract.permission.DataPolicyDecision;
import de.augmentia.oc.engine.core.model.SessionId;

public class NoOpDataPolicy implements DataPolicy {
    public static final NoOpDataPolicy INSTANCE = new NoOpDataPolicy();
    private NoOpDataPolicy() {}
    @Override
    public DataPolicyDecision evaluate(SessionId sessionId, String agent, String tool, String resource, Object output) {
        return DataPolicyDecision.allow();
    }
}
