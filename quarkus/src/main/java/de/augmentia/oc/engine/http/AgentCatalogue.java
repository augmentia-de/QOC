package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.AgentId;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Agent catalogue (L-1), fed from the L-2 configuration. */
@ApplicationScoped
public class AgentCatalogue {

    @Inject
    EngineConfig config;

    private final List<Agent> agents = new ArrayList<>();
    private final java.util.Map<String, EngineConfig.AgentConfig> configs = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, EngineConfig.AgentSkillsConfig> skillConfigs = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        // Preserve runtime-defined agents across reloads.
        java.util.Map<String, EngineConfig.AgentConfig> runtimeConfigs = new java.util.LinkedHashMap<>(configs);
        java.util.Map<String, EngineConfig.AgentSkillsConfig> runtimeSkills = new java.util.LinkedHashMap<>(skillConfigs);
        java.util.List<String> runtimeNames = new java.util.ArrayList<>(runtimeConfigs.keySet());

        this.agents.clear();
        this.configs.clear();
        // keep skillConfigs for runtime agents; clear disk ones (re-added below is not needed since disk agents have no runtime skills)

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
        // Re-apply runtime-defined agents that are not on disk.
        for (String name : runtimeNames) {
            if (configs.containsKey(name)) continue;
            EngineConfig.AgentConfig cfg = runtimeConfigs.get(name);
            if (cfg == null) continue;
            AgentId id = AgentId.create();
            this.agents.add(new Agent(id, cfg.name(), cfg.hidden()));
            configs.put(name, cfg);
            EngineConfig.AgentSkillsConfig sk = runtimeSkills.get(name);
            if (sk != null) skillConfigs.put(name, sk);
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

    /** Returns true if the named agent may be invoked as a subagent. */
    public boolean isSubagent(String name) {
        if (name == null) return false;
        EngineConfig.AgentConfig config = config(name);
        if (config != null) {
            String mode = config.mode();
            if ("primary".equalsIgnoreCase(mode)) return false;
            if ("subagent".equalsIgnoreCase(mode) || "all".equalsIgnoreCase(mode)) return true;
        }
        // Baseline: build and plan are primary agents.
        return !name.equalsIgnoreCase("build") && !name.equalsIgnoreCase("plan");
    }

    public EngineConfig.AgentConfig config(String name) {
        if (name == null) return null;
        return configs.get(name.toLowerCase());
    }

    /** Per-agent skill configuration, consulting runtime-defined agents first. */
    public EngineConfig.AgentSkillsConfig agentSkills(String name) {
        if (name == null) return null;
        EngineConfig.AgentSkillsConfig runtime = skillConfigs.get(name.toLowerCase());
        if (runtime != null) return runtime;
        return config != null ? config.agentSkills(name) : null;
    }

    /**
     * Defines (or replaces) an agent at runtime. The agent becomes available to
     * the {@code subagent} tool immediately. Skills are optional.
     */
    public synchronized void define(String name, EngineConfig.AgentConfig agentConfig,
                                    EngineConfig.AgentSkillsConfig skills) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("agent name must not be blank");
        if (agentConfig == null) throw new IllegalArgumentException("agent config must not be null");
        String key = name.toLowerCase();
        agents.removeIf(a -> a.name().equalsIgnoreCase(name));
        configs.put(key, agentConfig);
        if (skills != null) skillConfigs.put(key, skills);
        else skillConfigs.remove(key);
        agents.add(new Agent(AgentId.create(), name, agentConfig.hidden()));
    }

    public de.augmentia.oc.engine.core.model.AgentId resolve(String name) {
        if (name == null) return null;
        return agents.stream().filter(a -> a.name().equalsIgnoreCase(name))
                .findFirst().map(Agent::id).orElse(null);
    }

    private record Agent(AgentId id, String name, boolean hidden) {}

    public record AgentDto(String id, String name, boolean hidden) {}
}
