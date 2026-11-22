package de.augmentia.oc.engine.runtime.memory;

import de.augmentia.oc.engine.adapter.memory.InMemoryMemoryStore;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.port.store.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryImporterTest {

    @Test
    void importsFactsFromMarkdown(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("RULES.md");
        Files.writeString(file, "## Quarkus\n- Quarkus: version = 3.40\n- Java: version = 21\n");
        MemoryStore store = new InMemoryMemoryStore();
        int count = MemoryImporter.importFromMarkdown(store, file);
        assertEquals(2, count);
        assertFalse(store.query(new MemoryQuery("Quarkus", "version", null, null, 10)).isEmpty());
    }

    @Test
    void skipsDuplicateImport(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("RULES.md");
        Files.writeString(file, "- Quarkus: version = 3.40\n");
        MemoryStore store = new InMemoryMemoryStore();
        MemoryImporter.importFromMarkdown(store, file);
        int second = MemoryImporter.importFromMarkdown(store, file);
        assertEquals(0, second); // already present, skip
    }

    @Test
    void handlesMissingFile() {
        MemoryStore store = new InMemoryMemoryStore();
        int count = MemoryImporter.importFromMarkdown(store, Path.of("/nonexistent/file.md"));
        assertEquals(0, count);
    }

    @Test
    void handlesNullStore() {
        assertEquals(0, MemoryImporter.importFromMarkdown(null, Path.of("x.md")));
    }
}