package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.AgentId;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/** Agent catalogue (L-1), fed from the L-2 configuration. */
@ApplicationScoped
public class AgentCatalogue {

    @Inject
    EngineConfig config;

    private final List<Agent> agents = new ArrayList<>();

    @PostConstruct
    void load() {
        List<EngineConfig.AgentConfig> agents = config.agents();
        if (agents.isEmpty()) {
            agents = List.of(
                    new EngineConfig.AgentConfig("build", null, false),
                    new EngineConfig.AgentConfig("plan", "You are in plan mode. Do not edit files.", true));
        }
        for (EngineConfig.AgentConfig agent : agents) {
            this.agents.add(new Agent(AgentId.create(), agent.name(), agent.hidden()));
        }
    }

    public AgentId primaryId() {
        return agents.stream().filter(agent -> !agent.hidden()).findFirst()
                .orElse(agents.getFirst()).id();
    }

    public List<AgentDto> list() {
        return agents.stream().map(agent -> new AgentDto(agent.id().value(), agent.name(), agent.hidden()))
                .toList();
    }

    public String nameOf(de.augmentia.oc.engine.core.model.AgentId id) {
        return agents.stream().filter(a -> a.id().equals(id))
                .findFirst().map(Agent::name).orElse("build");
    }

    private record Agent(AgentId id, String name, boolean hidden) {}

    public record AgentDto(String id, String name, boolean hidden) {}
}
