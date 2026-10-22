package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.AgentId;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Minimal agent catalogue (L-1): one primary agent for the base environment. */
@ApplicationScoped
public class AgentCatalogue {

    private final AgentId primaryId = AgentId.create();

    public AgentId primaryId() {
        return primaryId;
    }

    public List<AgentDto> list() {
        return List.of(new AgentDto(primaryId.value(), "build", false));
    }

    public record AgentDto(String id, String name, boolean hidden) {}
}
