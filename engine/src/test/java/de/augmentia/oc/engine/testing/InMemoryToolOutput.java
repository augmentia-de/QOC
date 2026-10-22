package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.port.store.ToolOutputStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory managed storage for bounded tool output. */
public final class InMemoryToolOutput implements ToolOutputStore {

    private final Map<Path, byte[]> files = new LinkedHashMap<>();

    @Override
    public synchronized Path save(String suffix, byte[] bytes) {
        if (suffix == null || suffix.isBlank()) throw new IllegalArgumentException("suffix must not be blank");
        Path path = Path.of("/tool-output", "tool_" + suffix);
        files.put(path, bytes == null ? new byte[0] : bytes.clone());
        return path;
    }

    @Override
    public synchronized byte[] load(Path path) {
        byte[] content = files.get(path);
        if (content == null) throw new IllegalArgumentException("unknown output path: " + path);
        return content.clone();
    }

    @Override
    public synchronized void sweep(Instant olderThan) {
        files.clear();
    }

    public synchronized int size() {
        return files.size();
    }
}
