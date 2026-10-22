package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Rule;

import java.util.List;

/** Minimal agent view needed to build a model request. */
public record AgentProfile(AgentId id, Option<String> system, int maxSteps, List<Rule> permissions) {
    public AgentProfile {
        if (id == null) throw new IllegalArgumentException("id must not be null");
        if (system == null) system = Option.none();
        if (permissions == null) permissions = List.of();
        permissions = List.copyOf(permissions);
        if (maxSteps < 0) throw new IllegalArgumentException("maxSteps must not be negative");
    }
}
