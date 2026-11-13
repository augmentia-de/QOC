package de.augmentia.qoc.workflowrunner.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryArtifactStore implements ArtifactStore {

    private final Map<String, Object> payloads = new ConcurrentHashMap<>();
    private final Map<String, ArtifactRef> refs = new ConcurrentHashMap<>();

    @Override
    public String put(ArtifactRef ref, Object payload) {
        if (ref == null) throw new IllegalArgumentException("ref must not be null");
        refs.put(ref.id(), ref);
        payloads.put(ref.id(), payload);
        return ref.id();
    }

    @Override
    public Optional<Object> get(String id) {
        return Optional.ofNullable(payloads.get(id));
    }

    @Override
    public List<ArtifactRef> list() {
        return List.copyOf(new ArrayList<>(refs.values()));
    }

    @Override
    public boolean remove(String id) {
        boolean removed = refs.remove(id) != null;
        payloads.remove(id);
        return removed;
    }
}
