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
    private final java.util.Map<String, EngineConfig.AgentConfig> configs = new java.util.LinkedHashMap<>();

    @PostConstruct
    void load() {
        this.agents.clear();
        this.configs.clear();
        List<EngineConfig.AgentConfig> agents = config.agents();
        if (agents.isEmpty()) {
            agents = List.of(
                    new EngineConfig.AgentConfig("build", null, false),
                    new EngineConfig.AgentConfig("plan", "You are in plan mode. Do not edit files.", false));
        }
        for (EngineConfig.AgentConfig agent : agents) {
            AgentId id = AgentId.create();
            this.agents.add(new Agent(id, agent.name(), agent.hidden()));
            configs.put(agent.name().toLowerCase(), agent);
        }
    }

    public void reload() {
        load();
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

    /** Returns true if the named agent is a subagent (not primary). */
    public boolean isSubagent(String name) {
        // In the baseline, "build" and "plan" are primary; others are subagents.
        return name != null && !name.equalsIgnoreCase("build") && !name.equalsIgnoreCase("plan");
    }

    public EngineConfig.AgentConfig config(String name) {
        if (name == null) return null;
        return configs.get(name.toLowerCase());
    }

    public de.augmentia.oc.engine.core.model.AgentId resolve(String name) {
        if (name == null) return null;
        return agents.stream().filter(a -> a.name().equalsIgnoreCase(name))
                .findFirst().map(Agent::id).orElse(null);
    }

    private record Agent(AgentId id, String name, boolean hidden) {}

    public record AgentDto(String id, String name, boolean hidden) {}
}
