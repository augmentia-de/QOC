package de.augmentia.oc.engine.core.noop;

import de.augmentia.oc.engine.core.model.DecisionRequest;
import de.augmentia.oc.engine.core.model.DecisionResult;
import de.augmentia.oc.engine.core.port.runtime.DecisionModel;

import java.util.List;

public class NoOpDecisionModel implements DecisionModel {
    @Override
    public DecisionResult decide(DecisionRequest request) {
        return new DecisionResult(request.taskType(), "", List.of(), List.of(), 0.0, true, 0);
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
