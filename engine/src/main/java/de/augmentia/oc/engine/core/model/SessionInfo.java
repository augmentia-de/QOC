package de.augmentia.oc.engine.core.model;

import java.time.Instant;
import java.util.List;

public record SessionInfo(SessionId id, ProjectId projectId, Location location, String title,
                          AgentId agent, Option<ModelRef> model, List<Rule> permissions,
                          Money cost, TokenCount tokens, Instant created, Instant updated,
                          Option<Instant> idle, Option<Instant> suspended, int resumeAttempts,
                          Option<Instant> archived) {
    public SessionInfo {
        if (id == null || projectId == null || location == null || agent == null) {
            throw new IllegalArgumentException("session identity must not be null");
        }
        if (title == null) title = "";
        if (model == null) model = Option.none();
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        if (cost == null) cost = Money.zero();
        if (tokens == null) tokens = TokenCount.zero();
        if (created == null) created = Instant.EPOCH;
        if (updated == null) updated = created;
        if (idle == null) idle = Option.none();
        if (suspended == null) suspended = Option.none();
        if (archived == null) archived = Option.none();
        if (resumeAttempts < 0) throw new IllegalArgumentException("resumeAttempts must not be negative");
    }
}