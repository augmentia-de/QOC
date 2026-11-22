package de.augmentia.oc.engine.runtime.memory;

import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.port.store.MemoryStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Imports facts from Markdown files (RULES.md, AGENTS.md) into the memory store.
 * Only imports if the store is empty for the given entity (DB data takes precedence).
 * Supports simple fact syntax: "- entity: relation = value" or "## entity" sections.
 */
public final class MemoryImporter {

    private MemoryImporter() {}

    public static int importFromMarkdown(MemoryStore store, Path file) {
        if (store == null || file == null || !Files.exists(file)) return 0;
        int count = 0;
        try {
            List<String> lines = Files.readAllLines(file);
            String currentEntity = null;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("## ") || line.startsWith("# ")) {
                    currentEntity = line.replaceFirst("^#+\\s*", "").trim();
                    continue;
                }
                if (line.startsWith("- ") && line.contains(":") && line.contains("=")) {
                    String rest = line.substring(2).trim();
                    String[] parts = rest.split(":", 2);
                    if (parts.length < 2) continue;
                    String entity = parts[0].trim();
                    if (entity.isBlank()) entity = currentEntity;
                    if (entity == null || entity.isBlank()) continue;
                    String[] rv = parts[1].split("=", 2);
                    if (rv.length < 2) continue;
                    String relation = rv[0].trim();
                    String value = rv[1].trim();
                    if (relation.isBlank() || value.isBlank()) continue;
                    // Only import if no existing facts for this entity+relation
                    if (!store.query(new de.augmentia.oc.engine.core.model.MemoryQuery(entity, relation, null, null, 1)).isEmpty()) {
                        continue;
                    }
                    String factId = "import_" + UUID.nameUUIDFromBytes((entity + "|" + relation + "|" + value).getBytes()).toString().replace("-", "");
                    Fact fact = new Fact(factId, entity, relation, value, Instant.now(), Instant.now(), null, List.of(file.toString()), 0.8, 0);
                    store.upsert(fact);
                    count++;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return count;
    }
}