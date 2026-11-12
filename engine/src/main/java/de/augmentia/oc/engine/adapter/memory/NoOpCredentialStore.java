package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.Credential;
import de.augmentia.oc.engine.core.model.Option;

import java.util.List;
import java.util.Map;

/** No-op credential store for minimal (in-memory) mode. */
public final class NoOpCredentialStore implements de.augmentia.oc.engine.core.port.store.CredentialStore {
    @Override public List<Credential> saved(String integration) { return List.of(); }
    @Override public Option<Credential> get(String id) { return Option.none(); }
    @Override public Credential create(CredentialInput input) {
        throw new UnsupportedOperationException("credentials are not available in minimal in-memory mode");
    }
    @Override public void activate(String id) { /* no-op */ }
    @Override public void remove(String id) { /* no-op */ }
}