package de.augmentia.qoc.workflowrunner.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Persists workflow definitions as JSON files with version snapshots.
 * Ported from agentflow's WorkflowDefinitionStore.
 */
public class WorkflowStore {

    private final Path stateDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, WorkflowDefinition> cache = new ConcurrentHashMap<>();

    public WorkflowStore(String stateDir) {
        this.stateDir = Paths.get(stateDir == null || stateDir.isBlank() ? "./workflows" : stateDir);
        try {
            Files.createDirectories(this.stateDir);
            Files.createDirectories(this.stateDir.resolve("versions"));
        } catch (IOException e) {
            throw new RuntimeException("Cannot create state dir: " + this.stateDir, e);
        }
    }

    public WorkflowDefinition get(String id) {
        WorkflowDefinition cached = cache.get(id);
        if (cached != null) return cached;
        Path file = stateDir.resolve(id + ".json");
        if (!Files.exists(file)) return null;
        try {
            WorkflowDefinition def = mapper.readValue(Files.readString(file), WorkflowDefinition.class);
            cache.put(id, def);
            return def;
        } catch (IOException e) {
            throw new RuntimeException("Cannot read workflow: " + id, e);
        }
    }

    public boolean delete(String id) {
        cache.remove(id);
        Path file = stateDir.resolve(id + ".json");
        Path versionsDir = stateDir.resolve("versions").resolve(id);
        boolean deleted = false;
        try {
            if (Files.exists(file)) {
                Files.delete(file);
                deleted = true;
            }
            if (Files.exists(versionsDir)) {
                try (var stream = Files.walk(versionsDir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot delete workflow: " + id, e);
        }
        return deleted;
    }

    public List<String> list() {
        List<String> ids = new ArrayList<>();
        try (var stream = Files.list(stateDir)) {
            stream.filter(f -> f.toString().endsWith(".json"))
                    .map(f -> f.getFileName().toString().replace(".json", ""))
                    .forEach(ids::add);
        } catch (IOException e) {
            throw new RuntimeException("Cannot list workflows", e);
        }
        return ids;
    }

    public WorkflowDefinition save(String id, WorkflowDefinition def) {
        // Snapshot current version before overwriting
        WorkflowDefinition current = get(id);
        if (current != null) {
            snapshot(id, current);
        }
        // Write new version
        Path file = stateDir.resolve(id + ".json");
        try {
            Files.writeString(file, mapper.writeValueAsString(def));
            cache.put(id, def);
            return def;
        } catch (IOException e) {
            throw new RuntimeException("Cannot save workflow: " + id, e);
        }
    }

    public WorkflowDefinition create(String name) {
        String id = (name == null || name.isBlank()) ? "workflow-" + System.currentTimeMillis()
                : name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        WorkflowDefinition def = new WorkflowDefinition(
                id, name == null ? id : name, "1.0.0", "draft",
                List.of(
                        new WorkflowDefinition.Node("start", "start", new WorkflowDefinition.Point(100, 200),
                                new WorkflowDefinition.NodeData("Start", "", false, "human", 3, "", List.of(), "all", null, List.of(), "", "")),
                        new WorkflowDefinition.Node("end", "end", new WorkflowDefinition.Point(400, 200),
                                new WorkflowDefinition.NodeData("Done", "", false, "human", 3, "", List.of(), "all", null, List.of(), "", ""))),
                List.of(), List.of());
        save(id, def);
        return def;
    }

    public List<String> versions(String id) {
        Path vDir = stateDir.resolve("versions").resolve(id);
        List<String> versions = new ArrayList<>();
        if (!Files.exists(vDir)) return versions;
        try (var stream = Files.list(vDir)) {
            stream.filter(f -> f.toString().endsWith(".json"))
                    .map(f -> f.getFileName().toString().replace(".json", ""))
                    .sorted(Comparator.reverseOrder())
                    .forEach(versions::add);
        } catch (IOException e) {
            throw new RuntimeException("Cannot list versions: " + id, e);
        }
        return versions;
    }

    public WorkflowDefinition version(String id, String version) {
        Path file = stateDir.resolve("versions").resolve(id).resolve(version + ".json");
        if (!Files.exists(file)) return null;
        try {
            return mapper.readValue(Files.readString(file), WorkflowDefinition.class);
        } catch (IOException e) {
            throw new RuntimeException("Cannot read version: " + id + "/" + version, e);
        }
    }

    private void snapshot(String id, WorkflowDefinition def) {
        Path vDir = stateDir.resolve("versions").resolve(id);
        try {
            Files.createDirectories(vDir);
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .withZone(ZoneOffset.UTC).format(Instant.now());
            Path file = vDir.resolve(ts + ".json");
            Files.writeString(file, mapper.writeValueAsString(def));
        } catch (IOException e) {
            // Ignore snapshot failures
        }
    }
}
