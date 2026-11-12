package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.ArtifactRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tool output store with a bounded entry count (minimal mode).
 *
 * <p>Only the most recent {@link #MAX_ENTRIES} blobs are retained; when the
 * cap is exceeded the oldest entries (by insertion order of the underlying
 * concurrent map's key set) are evicted to keep the memory footprint bounded.</p>
 */
public final class InMemoryToolOutputStore implements ToolOutputStore {

    /** Maximum number of stored tool-output blobs. */
    public static final int MAX_ENTRIES = 256;

    private final RuntimeState state;

    public InMemoryToolOutputStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState() : state;
    }

    @Override
    public ArtifactRef save(SessionId session, String suffix, byte[] bytes) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        String id = (suffix == null ? "out" : suffix) + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String key = session.value() + ":" + id;
        byte[] payload = bytes == null ? new byte[0] : bytes;
        state.toolOutputs.put(key, payload);
        evictIfNeeded();
        return new ArtifactRef(session, id, "application/octet-stream", "sha256-placeholder", payload.length);
    }

    @Override
    public Option<ArtifactRef> find(SessionId session, String id) {
        if (session == null || id == null || id.isBlank()) return Option.none();
        String key = session.value() + ":" + id;
        byte[] bytes = state.toolOutputs.get(key);
        if (bytes == null) return Option.none();
        return Option.some(new ArtifactRef(session, id, "application/octet-stream", "sha256-placeholder", bytes.length));
    }

    @Override
    public byte[] load(SessionId session, String id) {
        if (session == null || id == null || id.isBlank()) return null;
        String key = session.value() + ":" + id;
        return state.toolOutputs.get(key);
    }

    @Override
    public void sweep(Instant olderThan) {
        // In-memory: no-op (no timestamps stored)
    }

    private void evictIfNeeded() {
        Map<String, byte[]> m = state.toolOutputs;
        if (m.size() <= MAX_ENTRIES) return;
        int toRemove = m.size() - MAX_ENTRIES;
        java.util.Iterator<String> it = m.keySet().iterator();
        int removed = 0;
        while (it.hasNext() && removed < toRemove) {
            it.next();
            it.remove();
            removed++;
        }
    }
}