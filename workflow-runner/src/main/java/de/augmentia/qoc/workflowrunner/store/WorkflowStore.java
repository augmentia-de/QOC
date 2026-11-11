package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-based {@link WorkflowDefinitionStore}. Definitions live as JSON files;
 * every content change keeps the previous definition as a version snapshot.
 */
public class WorkflowStore implements WorkflowDefinitionStore {

    private final Path stateDir;
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

    @Override
    public WorkflowDefinition get(String id) {
        WorkflowDefinition cached = cache.get(id);
        if (cached != null) return cached;
        Path file = stateDir.resolve(id + ".json");
        if (!Files.exists(file)) return null;
        try {
            WorkflowDefinition def = WorkflowJson.read(Files.readString(file));
            cache.put(id, def);
            return def;
        } catch (IOException e) {
            throw new RuntimeException("Cannot read workflow: " + id, e);
        }
    }

    @Override
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

    @Override
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

    @Override
    public WorkflowDefinition save(String id, WorkflowDefinition def) {
        WorkflowDefinition normalized = WorkflowDefaults.withId(id, def);
        WorkflowDefinition current = get(id);
        if (current != null
                && !WorkflowJson.contentHash(current).equals(WorkflowJson.contentHash(normalized))) {
            snapshotCurrent(id, current);
        }
        Path file = stateDir.resolve(id + ".json");
        try {
            writeAtomic(file, WorkflowJson.write(normalized));
            cache.put(id, normalized);
            return normalized;
        } catch (IOException e) {
            throw new RuntimeException("Cannot save workflow: " + id, e);
        }
    }

    @Override
    public WorkflowDefinition create(String name) {
        WorkflowDefinition def = WorkflowDefaults.create(name);
        save(def.id(), def);
        return def;
    }

    @Override
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

    @Override
    public WorkflowDefinition version(String id, String version) {
        Path file = stateDir.resolve("versions").resolve(id).resolve(version + ".json");
        if (!Files.exists(file)) return null;
        try {
            return WorkflowJson.read(Files.readString(file));
        } catch (IOException e) {
            throw new RuntimeException("Cannot read version: " + id + "/" + version, e);
        }
    }

    @Override
    public void snapshot(String id, String version, WorkflowDefinition def) {
        Path vDir = stateDir.resolve("versions").resolve(id);
        try {
            Files.createDirectories(vDir);
            writeAtomic(vDir.resolve(version + ".json"),
                    WorkflowJson.write(WorkflowDefaults.withId(id, def)));
        } catch (IOException e) {
            throw new RuntimeException("Cannot snapshot workflow: " + id + "/" + version, e);
        }
    }

    @Override
    public WorkflowDefinition findByHash(String id, String contentHash) {
        WorkflowDefinition current = get(id);
        if (current != null && WorkflowJson.contentHash(current).equals(contentHash)) {
            return current;
        }
        for (String version : versions(id)) {
            WorkflowDefinition candidate = version(id, version);
            if (candidate != null && WorkflowJson.contentHash(candidate).equals(contentHash)) {
                return candidate;
            }
        }
        return null;
    }

    private void snapshotCurrent(String id, WorkflowDefinition def) {
        snapshot(id, WorkflowJson.versionId(), def);
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
