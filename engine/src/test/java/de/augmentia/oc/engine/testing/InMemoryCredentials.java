package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.Credential;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.store.CredentialStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory credential store; exactly one active credential per integration. */
public final class InMemoryCredentials implements CredentialStore {

    private final Map<String, Credential> credentials = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public synchronized List<Credential> saved(String integration) {
        List<Credential> result = new ArrayList<>();
        for (Credential credential : credentials.values()) {
            if (credential.integration().equals(integration)) result.add(credential);
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized Option<Credential> get(String id) {
        return Option.of(credentials.get(id));
    }

    @Override
    public synchronized Credential create(CredentialInput input) {
        if (input == null) throw new IllegalArgumentException("input must not be null");
        deactivateAll(input.integration());
        Credential credential = new Credential("cred_" + sequence.incrementAndGet(),
                input.integration(), input.kind(), Option.some(input.secret()), true,
                Instant.ofEpochMilli(sequence.get()));
        credentials.put(credential.id(), credential);
        return credential;
    }

    @Override
    public synchronized void activate(String id) {
        Credential credential = require(id);
        deactivateAll(credential.integration());
        credentials.put(id, withActive(credential, true));
    }

    @Override
    public synchronized void remove(String id) {
        credentials.remove(id);
    }

    private void deactivateAll(String integration) {
        for (Map.Entry<String, Credential> entry : credentials.entrySet()) {
            if (entry.getValue().integration().equals(integration) && entry.getValue().active()) {
                entry.setValue(withActive(entry.getValue(), false));
            }
        }
    }

    private Credential require(String id) {
        Credential credential = credentials.get(id);
        if (credential == null) throw new IllegalArgumentException("unknown credential: " + id);
        return credential;
    }

    private static Credential withActive(Credential credential, boolean active) {
        return new Credential(credential.id(), credential.integration(), credential.kind(),
                credential.secret(), active, credential.created());
    }
}
