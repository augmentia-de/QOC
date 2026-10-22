package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.PolicyStatement;
import de.augmentia.oc.engine.core.pattern.Wildcard;

import java.util.List;

/**
 * The organization-policy view of the rule chain. Policy rules are applied
 * <em>last</em>: a deny has final authority, an allow never grants access.
 */
public final class PolicyRules {

    public static final String TOOL_USE = "tool.use";
    public static final String PROVIDER_USE = "provider.use";
    public static final String INTEGRATION_USE = "integration.use";

    private PolicyRules() {}

    /** True when any {@code tool.use} policy denies the action/resource pair. */
    public static boolean deniesToolUse(List<PolicyStatement> policies, String action, String resource) {
        return matches(policies, TOOL_USE, toolUseResource(action, resource), Effect.DENY);
    }

    /** True when any {@code tool.use} policy allows the action/resource pair. */
    public static boolean allowsToolUse(List<PolicyStatement> policies, String action, String resource) {
        return matches(policies, TOOL_USE, toolUseResource(action, resource), Effect.ALLOW);
    }

    /** True when a provider is forbidden by a {@code provider.use} policy. */
    public static boolean deniesProvider(List<PolicyStatement> policies, String providerId) {
        return matches(policies, PROVIDER_USE, providerId, Effect.DENY);
    }

    /** True when an integration (mcp/plugin/hosted/skill) is forbidden. */
    public static boolean deniesIntegration(List<PolicyStatement> policies, String integration) {
        return matches(policies, INTEGRATION_USE, integration, Effect.DENY);
    }

    private static boolean matches(List<PolicyStatement> policies, String action, String resource, Effect effect) {
        if (policies == null) return false;
        for (PolicyStatement policy : policies) {
            if (policy.effect() == effect
                    && Wildcard.matches(action, policy.action())
                    && Wildcard.matches(resource, policy.resource())) {
                return true;
            }
        }
        return false;
    }

    private static String toolUseResource(String action, String resource) {
        return action + ":" + resource;
    }
}
