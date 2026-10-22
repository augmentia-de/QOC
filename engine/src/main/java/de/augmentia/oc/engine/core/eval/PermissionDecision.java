package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;

import java.util.List;

/**
 * Outcome of the pure permission check. A denial is model-facing (channel 1);
 * a user rejection without feedback is a separate abort and never appears here.
 */
public sealed interface PermissionDecision {

    record Allowed(Option<String> message) implements PermissionDecision {
        public Allowed {
            if (message == null) message = Option.none();
        }
    }

    record Ask(Option<String> message) implements PermissionDecision {
        public Ask {
            if (message == null) message = Option.none();
        }
    }

    record Denied(String reason, List<Rule> triggeringRules) implements PermissionDecision {
        public Denied {
            if (reason == null || reason.isBlank()) reason = "permission denied";
            triggeringRules = triggeringRules == null ? List.of() : List.copyOf(triggeringRules);
        }
    }
}
