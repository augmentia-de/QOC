package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.AgentId;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Agent catalogue (L-1), fed from the L-2 configuration. */
@ApplicationScoped
public class AgentCatalogue {

    @Inject
    EngineConfig config;

    private final List<Agent> agents = new ArrayList<>();
    private final java.util.Map<String, EngineConfig.AgentConfig> configs = new java.util.LinkedHashMap<>();
    /**
     * Agents created through {@link #define} at runtime. Only these survive a
     * reload; agents loaded from a workspace must disappear when the workspace
     * changes, otherwise the previous workspace's agents leak into the new one.
     */
    private final java.util.Map<String, EngineConfig.AgentConfig> runtimeConfigs =
            new java.util.LinkedHashMap<>();
    private final java.util.Map<String, EngineConfig.AgentSkillsConfig> skillConfigs = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        // Preserve runtime-defined agents across reloads.
        java.util.Map<String, EngineConfig.AgentConfig> persistedConfigs =
                new java.util.LinkedHashMap<>(runtimeConfigs);
        java.util.Map<String, EngineConfig.AgentSkillsConfig> persistedSkills =
                new java.util.LinkedHashMap<>(skillConfigs);
        java.util.List<String> persistedNames = new java.util.ArrayList<>(persistedConfigs.keySet());

        this.agents.clear();
        this.configs.clear();
        // skillConfigs only contains runtime-defined skills; keep them.

        List<EngineConfig.AgentConfig> agents = config.agents();
        if (agents.isEmpty()) {
            agents = List.of(
                    new EngineConfig.AgentConfig("build", null, false),
                    new EngineConfig.AgentConfig("plan", "You are in plan mode. Do not edit files.", false));
        }
        for (EngineConfig.AgentConfig agent : agents) {
            AgentId id = stableId(agent.name());
            this.agents.add(new Agent(id, agent.name(), agent.hidden()));
            configs.put(agent.name().toLowerCase(), agent);
        }
        // Re-apply runtime-defined agents that are not on disk.
        for (String name : persistedNames) {
            if (configs.containsKey(name)) continue;
            EngineConfig.AgentConfig cfg = persistedConfigs.get(name);
            if (cfg == null) continue;
            AgentId id = stableId(cfg.name());
            this.agents.add(new Agent(id, cfg.name(), cfg.hidden()));
            configs.put(name, cfg);
            EngineConfig.AgentSkillsConfig sk = persistedSkills.get(name);
            if (sk != null) skillConfigs.put(name, sk);
        }
    }

    /**
     * Deterministic agent ID, derived from the agent name. Sessions store this
     * ID, so a config reload must not invalidate them. Random IDs made every
     * reload break running sessions: the stale ID fell back to "build" and its
     * deny rules blocked subagent/glob/grep.
     */
    private static AgentId stableId(String name) {
        String seed = "qoc-agent:" + (name == null ? "" : name.toLowerCase(Locale.ROOT));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            return AgentId.parse("agt_" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
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
                .findFirst().map(Agent::name).orElse(null);
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

    /** Returns true when the principal roles may use the named agent. */
    public boolean isAllowed(String name, List<String> principalRoles) {
        return AgentAccess.allowed(config(name), principalRoles);
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
        runtimeConfigs.put(key, agentConfig);
        if (skills != null) skillConfigs.put(key, skills);
        else skillConfigs.remove(key);
        agents.add(new Agent(stableId(name), name, agentConfig.hidden()));
    }

    /**
     * Persists an agent definition to disk based on the persistent flag.
     * NONE = in-memory only, GLOBAL = $HOME/.config/opencode/agents/,
     * LOCAL = workspace/.opencode/agents/.
     */
    public void persistAgent(String name, String content, String persistent) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("agent name must not be blank");
        if (content == null) content = "";
        String safe = name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) throw new IllegalArgumentException("invalid agent name");
        String mode = persistent == null ? "NONE" : persistent.trim().toUpperCase();
        java.nio.file.Path dir = null;
        switch (mode) {
            case "GLOBAL" -> dir = java.nio.file.Path.of(System.getProperty("user.home", "."),
                    ".config", "opencode", "agents");
            case "LOCAL" -> {
                String ws = config == null ? "." : config.workspace();
                if (ws == null || ws.isBlank()) ws = ".";
                dir = java.nio.file.Path.of(ws, ".opencode", "agents");
            }
            default -> { return; } // NONE: in-memory only
        }
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path file = dir.resolve(safe + ".md");
            java.nio.file.Files.writeString(file, content, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot persist agent: " + name, e);
        }
    }

    public de.augmentia.oc.engine.core.model.AgentId resolve(String name) {
        if (name == null) return null;
        return agents.stream().filter(a -> a.name().equalsIgnoreCase(name))
                .findFirst().map(Agent::id).orElse(null);
    }

    /** Removes a transient agent (serverless on-demand fetch) again. */
    public synchronized void remove(String name) {
        if (name == null || name.isBlank()) return;
        String key = name.toLowerCase();
        agents.removeIf(a -> a.name().equalsIgnoreCase(name));
        configs.remove(key);
        runtimeConfigs.remove(key);
        skillConfigs.remove(key);
    }

    private record Agent(AgentId id, String name, boolean hidden) {}

    public record AgentDto(String id, String name, boolean hidden) {}
}
