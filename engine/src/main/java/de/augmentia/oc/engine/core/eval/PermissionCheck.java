package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.SavedApproval;

import java.util.List;

/**
 * Everything the pure rule chain needs. The rule order is binding: agent rules
 * first, then session rules (P-07).
 */
public record PermissionCheck(
        PermissionRequest request,
        Option<AgentId> agent,
        List<Rule> agentRules,
        List<Rule> sessionRules,
        List<SavedApproval> approvals,
        List<PolicyStatement> policies) {

    public PermissionCheck {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (agent == null) agent = Option.none();
        agentRules = agentRules == null ? List.of() : List.copyOf(agentRules);
        sessionRules = sessionRules == null ? List.of() : List.copyOf(sessionRules);
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    /** Agent rules followed by session rules; the last matching rule wins. */
    public List<Rule> rules() {
        if (agentRules.isEmpty()) return sessionRules;
        if (sessionRules.isEmpty()) return agentRules;
        return java.util.stream.Stream.concat(agentRules.stream(), sessionRules.stream()).toList();
    }
}
