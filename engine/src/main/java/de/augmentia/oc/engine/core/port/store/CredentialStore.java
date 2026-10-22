package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.Credential;
import de.augmentia.oc.engine.core.model.Option;

import java.util.List;

/** Saved integration credentials. Secrets come from the environment, not the config file. */
public interface CredentialStore {

    List<Credential> saved(String integration);

    Option<Credential> get(String id);

    Credential create(CredentialInput input);

    void activate(String id);

    void remove(String id);

    record CredentialInput(String integration, String kind, String secret) {
        public CredentialInput {
            if (integration == null || integration.isBlank()) {
                throw new IllegalArgumentException("integration must not be blank");
            }
            if (kind == null || kind.isBlank()) kind = "api-key";
            if (secret == null) secret = "";
        }
    }
}
