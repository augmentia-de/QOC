package de.augmentia.qoc.workflowrunner.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Persists agent definitions as JSON files.
 * Agents are stored globally and can be referenced by ID from multiple workflows.
 */
public class AgentStore {

    private final Path stateDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Agent> cache = new ConcurrentHashMap<>();

    public AgentStore(Path parentDir) {
        this.stateDir = parentDir.resolve("agents");
        try {
            Files.createDirectories(this.stateDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create agent store dir: " + this.stateDir, e);
        }
    }

    public Agent get(String id) {
        Agent cached = cache.get(id);
        if (cached != null) return cached;
        Path file = stateDir.resolve(id + ".json");
        if (!Files.exists(file)) return null;
        try {
            Agent agent = mapper.readValue(Files.readString(file), Agent.class);
            cache.put(id, agent);
            return agent;
        } catch (IOException e) {
            throw new RuntimeException("Cannot read agent: " + id, e);
        }
    }

    public List<String> list() {
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(stateDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .forEach(ids::add);
        } catch (IOException e) {
            // ignore
        }
        return ids;
    }

    public List<Agent> listAll() {
        List<Agent> agents = new ArrayList<>();
        for (String id : list()) {
            Agent a = get(id);
            if (a != null) agents.add(a);
        }
        return agents;
    }

    public Agent save(String id, Agent agent) {
        cache.remove(id);
        Path file = stateDir.resolve(id + ".json");
        try {
            if (agent.id() == null || agent.id().isBlank()) {
                agent = new Agent(id, agent.name(), agent.version(), agent.category(), agent.model(),
                        agent.temperature(), agent.maxTokens(), agent.topP(), agent.tools(),
                        agent.guardrailsInput(), agent.guardrailsOutput(), agent.hooks(),
                        agent.jsonOutput(), agent.active(), agent.systemPrompt());
            }
            mapper.writeValue(file.toFile(), agent);
            cache.put(id, agent);
            return agent;
        } catch (IOException e) {
            throw new RuntimeException("Cannot save agent: " + id, e);
        }
    }

    public Agent create(String name) {
        String id = name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        if (id.isBlank()) id = "agent-" + System.currentTimeMillis();
        Agent agent = new Agent(id, name, "1.0.0", "", "deepseek-v4.1-flash", 0.3, 4096, 1.0,
                List.of(), List.of(), List.of(), List.of(), false, true, "");
        return save(id, agent);
    }

    public boolean delete(String id) {
        cache.remove(id);
        Path file = stateDir.resolve(id + ".json");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new RuntimeException("Cannot delete agent: " + id, e);
        }
    }
}