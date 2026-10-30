package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Effect;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;

import java.util.List;

/**
 * Minimal agent view needed to build a model request.
 *
 * <p>{@code maxSteps == 0} means no step limit. A positive value is the
 * maximum number of model rounds for one prompt.</p>
 */
public record AgentProfile(AgentId id, Option<String> system, int maxSteps, List<Rule> permissions,
                           boolean planMode) {
    public AgentProfile {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (system == null) system = Option.none();
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        if (maxSteps < 0) throw new IllegalArgumentException("maxSteps must not be negative; 0 means no limit");
    }

    public AgentProfile(AgentId id, Option<String> system, int maxSteps, List<Rule> permissions) {
        this(id, system, maxSteps, permissions, false);
    }

    /** Plan mode is detected from the agent name. */
    public static AgentProfile forAgent(AgentId id, Option<String> system, int maxSteps,
                                        List<Rule> permissions, String name) {
        boolean plan = "plan".equalsIgnoreCase(name);
        // Auto-detect plan mode only when edit is denied globally. Scoped rules
        // such as "edit: *.env = deny" must not turn a build agent into plan mode.
        if (!plan && permissions != null) {
            plan = permissions.stream().anyMatch(r ->
                    "edit".equalsIgnoreCase(r.action())
                            && ("*".equals(r.resource()) || "**".equals(r.resource()))
                            && r.effect() == Effect.DENY);
        }
        return new AgentProfile(id, system, maxSteps, permissions, plan);
    }
}
