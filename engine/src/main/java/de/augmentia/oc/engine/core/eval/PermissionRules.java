package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.contract.permission.PermissionEvaluation;
import de.augmentia.oc.engine.core.contract.permission.PermissionHook;
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;
import de.augmentia.oc.engine.core.model.SavedApproval;
import de.augmentia.oc.engine.core.pattern.Wildcard;

import java.util.ArrayList;
import java.util.List;

/**
 * The pure permission rule chain (permissions/02-evaluation.md).
 *
 * <ol>
 *   <li>Build the rule set (agent rules, then session rules).</li>
 *   <li>Denial pre-check: one denied resource blocks the whole operation.</li>
 *   <li>Add stored approvals.</li>
 *   <li>Ask dominates allow.</li>
 *   <li>Run the extension hook.</li>
 * </ol>
 */
public final class PermissionRules {

    /** Default when no rule exists at all: deny everything. */
    public static final Rule DENY_ALL = new Rule("*", "*", Effect.DENY);

    /** Default when rules exist but none matches: ask (P-02). */
    public static final Effect NO_MATCH_EFFECT = Effect.ASK;

    private PermissionRules() {}

    public static PermissionDecision decide(PermissionCheck check) {
        return decide(check, Option.none());
    }

    public static PermissionDecision decide(PermissionCheck check, Option<PermissionHook> hook) {
        if (check == null) throw new IllegalArgumentException("check must not be null");
        if (hook == null) hook = Option.none();

        // P-06: without an agent everything is denied.
        if (check.agent().isEmpty()) {
            return new PermissionDecision.Denied("no agent", List.of());
        }

        List<Rule> rules = check.rules();
        if (rules.isEmpty()) {
            rules = List.of(DENY_ALL);
        }

        // A policy deny has final authority - even over stored approvals.
        for (String resource : check.request().resources()) {
            if (PolicyRules.deniesToolUse(check.policies(), check.request().action(), resource)) {
                return new PermissionDecision.Denied("policy denied", List.of());
            }
        }

        // Step 2: denial pre-check on the configured rules only.
        for (String resource : check.request().resources()) {
            Effect effect = evaluate(rules, check.request().action(), resource,
                    PolicyRules.allowsToolUse(check.policies(), check.request().action(), resource));
            if (effect == Effect.DENY) {
                return new PermissionDecision.Denied("denied by rule", triggeringRules(rules,
                        check.request().action(), resource));
            }
        }

        // Step 3: stored approvals are appended, so they can lift allow/ask.
        List<Rule> allRules = withApprovals(rules, check.approvals());

        // Step 4: ask dominates allow. No match means ask.
        boolean ask = false;
        for (String resource : check.request().resources()) {
            Effect effect = evaluate(allRules, check.request().action(), resource,
                    PolicyRules.allowsToolUse(check.policies(), check.request().action(), resource));
            if (effect == Effect.ASK) {
                ask = true;
                break;
            }
        }

        Effect effect = ask ? Effect.ASK : Effect.ALLOW;
        PermissionEvaluation evaluation = new PermissionEvaluation(
                check.request().sessionId(), check.agent(), check.request().action(),
                check.request().resources(), check.request().metadata(), effect);

        if (hook.isPresent()) {
            Result<PermissionEvaluation> hooked = hook.orElseThrow().evaluate(evaluation);
            if (hooked instanceof Result.Err<PermissionEvaluation> error) {
                throw new Defect("permission hook failed: " + error.error().message());
            }
            evaluation = hooked.valueOrNull();
        }

        return switch (evaluation.effect()) {
            case ALLOW -> new PermissionDecision.Allowed(evaluation.message());
            case ASK -> new PermissionDecision.Ask(evaluation.message());
            case DENY -> new PermissionDecision.Denied(
                    evaluation.message().orElse("denied by hook"), List.of());
        };
    }

    /**
     * Evaluates one resource against an ordered rule set. The last matching
     * rule wins. A policy allow lifts a matching deny (it never grants).
     */
    public static Effect evaluate(List<Rule> rules, String action, String resource, boolean policyAllows) {
        Effect result = NO_MATCH_EFFECT;
        for (Rule rule : rules) {
            if (matches(rule, action, resource)) {
                if (policyAllows && rule.effect() == Effect.DENY) {
                    continue;
                }
                result = rule.effect();
            }
        }
        return result;
    }

    public static Effect evaluate(List<Rule> rules, String action, String resource) {
        return evaluate(rules, action, resource, false);
    }

    public static boolean matches(Rule rule, String action, String resource) {
        return Wildcard.match(action, rule.action()) && Wildcard.match(resource, rule.resource());
    }

    private static List<Rule> triggeringRules(List<Rule> rules, String action, String resource) {
        List<Rule> triggering = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.effect() == Effect.DENY && matches(rule, action, resource)) {
                triggering.add(rule);
            }
        }
        return List.copyOf(triggering);
    }

    private static List<Rule> withApprovals(List<Rule> rules, List<SavedApproval> approvals) {
        if (approvals.isEmpty()) return rules;
        List<Rule> all = new ArrayList<>(rules);
        for (SavedApproval approval : approvals) {
            all.add(new Rule(approval.action(), approval.resource(), Effect.ALLOW));
        }
        return List.copyOf(all);
    }
}
