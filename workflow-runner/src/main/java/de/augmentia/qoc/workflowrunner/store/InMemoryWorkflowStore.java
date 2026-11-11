package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Heap-backed workflow definition store for the {@code memory} deployment
 * profile. Definitions disappear on restart by design; run state follows the
 * same lifecycle because the engine's journal is also in memory.
 */
public class InMemoryWorkflowStore implements WorkflowDefinitionStore {

    private final Map<String, WorkflowDefinition> current = new ConcurrentHashMap<>();
    private final Map<String, Map<String, WorkflowDefinition>> versions = new ConcurrentHashMap<>();

    @Override
    public WorkflowDefinition get(String id) {
        return current.get(id);
    }

    @Override
    public boolean delete(String id) {
        versions.remove(id);
        return current.remove(id) != null;
    }

    @Override
    public List<String> list() {
        return new ArrayList<>(current.keySet());
    }

    @Override
    public synchronized WorkflowDefinition save(String id, WorkflowDefinition definition) {
        WorkflowDefinition normalized = WorkflowDefaults.withId(id, definition);
        WorkflowDefinition previous = current.get(id);
        if (previous != null
                && !WorkflowJson.contentHash(previous).equals(WorkflowJson.contentHash(normalized))) {
            versions.computeIfAbsent(id, ignored -> new ConcurrentHashMap<>())
                    .put(WorkflowJson.versionId(), previous);
        }
        current.put(id, normalized);
        return normalized;
    }

    @Override
    public WorkflowDefinition create(String name) {
        WorkflowDefinition definition = WorkflowDefaults.create(name);
        save(definition.id(), definition);
        return definition;
    }

    @Override
    public List<String> versions(String id) {
        Map<String, WorkflowDefinition> snapshots = versions.get(id);
        if (snapshots == null) return List.of();
        List<String> ids = new ArrayList<>(snapshots.keySet());
        ids.sort(Comparator.reverseOrder());
        return ids;
    }

    @Override
    public WorkflowDefinition version(String id, String version) {
        Map<String, WorkflowDefinition> snapshots = versions.get(id);
        return snapshots == null ? null : snapshots.get(version);
    }

    @Override
    public synchronized void snapshot(String id, String version, WorkflowDefinition definition) {
        versions.computeIfAbsent(id, ignored -> new ConcurrentHashMap<>())
                .put(version, WorkflowDefaults.withId(id, definition));
    }

    @Override
    public WorkflowDefinition findByHash(String id, String contentHash) {
        WorkflowDefinition definition = current.get(id);
        if (definition != null && WorkflowJson.contentHash(definition).equals(contentHash)) {
            return definition;
        }
        Map<String, WorkflowDefinition> snapshots = versions.get(id);
        if (snapshots == null) return null;
        for (WorkflowDefinition candidate : new LinkedHashMap<>(snapshots).values()) {
            if (WorkflowJson.contentHash(candidate).equals(contentHash)) {
                return candidate;
            }
        }
        return null;
    }
}
