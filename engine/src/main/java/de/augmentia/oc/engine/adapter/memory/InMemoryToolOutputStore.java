package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.ArtifactRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory tool output store: content-addressed blobs per session.
 */
public final class InMemoryToolOutputStore implements ToolOutputStore {

    private final RuntimeState state;

    public InMemoryToolOutputStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    @Override
    public ArtifactRef save(SessionId session, String suffix, byte[] bytes) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        String id = (suffix == null ? "out" : suffix) + "_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String key = session.value() + ":" + id;
        state.toolOutputs.put(key, bytes == null ? new byte[0] : bytes);
        return new ArtifactRef(session, id, "application/octet-stream", "sha256-placeholder", bytes == null ? 0 : bytes.length);
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
}
