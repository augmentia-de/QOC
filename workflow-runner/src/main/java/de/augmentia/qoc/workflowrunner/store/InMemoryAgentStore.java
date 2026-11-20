package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory agent store. */
public class InMemoryAgentStore implements AgentStorePort {
    private final Map<String, Agent> store = new ConcurrentHashMap<>();

    @Override
    public Agent get(String id) { return store.get(id); }

    @Override
    public boolean delete(String id) { return store.remove(id) != null; }

    @Override
    public List<String> list() { return new ArrayList<>(store.keySet()); }

    @Override
    public List<Agent> listAll() { return listAll(null, true); }

    @Override
    public List<Agent> listAll(String userId, boolean isAdmin) {
        List<Agent> result = new ArrayList<>();
        for (Agent a : store.values()) {
            if (isAdmin || userId == null || userId.isBlank()) {
                result.add(a);
            } else {
                String owner = a.ownerId();
                if (owner == null || owner.isBlank() || owner.equals(userId)) {
                    result.add(a);
                }
            }
        }
        return result;
    }

    @Override
    public Agent save(String id, Agent agent) {
        if (agent.id() == null || agent.id().isBlank()) {
            agent = new Agent(id, agent.name(), agent.version(), agent.category(), agent.model(),
                    agent.temperature(), agent.maxTokens(), agent.topP(), agent.tools(),
                    agent.guardrailsInput(), agent.guardrailsOutput(), agent.hooks(),
                    agent.jsonOutput(), agent.active(), agent.systemPrompt(), agent.responseSchema(),
                    agent.ownerId(), agent.rolesAllowed(),
                    agent.mode() == null ? "all" : agent.mode(),
                    agent.skills() == null ? java.util.List.of() : agent.skills(),
                    agent.maxTurns(), agent.maxCost(), agent.requiresHITL(),
                    agent.permissions() == null ? java.util.List.of() : agent.permissions());
        }
        store.put(id, agent);
        return agent;
    }

    @Override
    public Agent create(String name) {
        String id = name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        if (id.isBlank()) id = "agent-" + System.currentTimeMillis();
        Agent agent = new Agent(id, name, "1.0.0", "", "deepseek-v4.1-flash", 0.3, 4096, 1.0,
                List.of(), List.of(), List.of(), List.of(), false, true, "", null, "", List.of("*"),
                "all", List.of(), 10, 1.0, false, java.util.List.of());
        return save(id, agent);
    }
}