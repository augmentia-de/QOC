package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.Credential;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.store.CredentialStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory credential store: credentials per integration in a list.
 */
public final class InMemoryCredentialStore implements CredentialStore {

    private final RuntimeState state;

    public InMemoryCredentialStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    private List<Credential> list(String integration) {
        return (List<Credential>) state.credentials.computeIfAbsent(integration, k -> new ArrayList<Credential>());
    }

    @Override
    public List<Credential> saved(String integration) {
        if (integration == null || integration.isBlank()) return List.of();
        List<Credential> l = list(integration);
        synchronized (l) {
            return List.copyOf(l);
        }
    }

    @Override
    public Option<Credential> get(String id) {
        if (id == null || id.isBlank()) return Option.none();
        for (Object o : state.credentials.values()) {
            if (o instanceof List) {
                for (Object c : (List<?>) o) {
                    if (c instanceof Credential && ((Credential) c).id().equals(id)) {
                        return Option.some((Credential) c);
                    }
                }
            }
        }
        return Option.none();
    }

    @Override
    public Credential create(CredentialInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        Credential c = new Credential(java.util.UUID.randomUUID().toString(), input.integration(),
                input.kind(), Option.some(input.secret()), false, java.time.Instant.now(), input.metadata());
        List<Credential> l = list(input.integration());
        synchronized (l) { l.add(c); }
        return c;
    }

    @Override
    public void activate(String id) {
        // In-memory: mark active by setting a flag (simplified)
    }

    @Override
    public void remove(String id) {
        if (id == null || id.isBlank()) return;
        for (Object o : state.credentials.values()) {
            if (o instanceof List) {
                ((List<?>) o).removeIf(c -> c instanceof Credential && ((Credential) c).id().equals(id));
            }
        }
    }
}
