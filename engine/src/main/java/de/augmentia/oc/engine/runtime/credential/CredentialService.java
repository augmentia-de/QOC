package de.augmentia.oc.engine.runtime.credential;

import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.model.Credential;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.store.CredentialStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Credential resolution (CR-03/CR-04): active stored, further stored (newest
 * first), then environment. If one stored credential exists, the environment
 * is not considered. Secrets never appear in messages.
 */
public final class CredentialService {

    private static final Comparator<Credential> ORDER = Comparator
            .comparing(Credential::active).reversed()
            .thenComparing(Credential::created, Comparator.reverseOrder());

    private final Option<CredentialStore> store;
    private final Function<String, String> environment;

    public CredentialService(Option<CredentialStore> store, Function<String, String> environment) {
        this.store = store == null ? Option.none() : store;
        this.environment = environment == null ? name -> null : environment;
    }

    public static CredentialService envOnly(Function<String, String> environment) {
        return new CredentialService(Option.none(), environment);
    }

    public List<Credential> stored(String integrationId) {
        if (store.isEmpty()) return List.of();
        return store.orElseThrow().saved(integrationId).stream().sorted(ORDER).toList();
    }

    public List<Connection> connections(Integration integration) {
        if (integration == null) throw new IllegalArgumentException("integration must not be null");
        List<Credential> stored = stored(integration.id());
        if (!stored.isEmpty()) {
            List<Connection> connections = new ArrayList<>();
            for (Credential credential : stored) {
                connections.add(Connection.stored(integration.id(), credential));
            }
            return List.copyOf(connections);
        }

        List<Connection> connections = new ArrayList<>();
        for (String envName : integration.envNames()) {
            if (isSet(envName)) {
                connections.add(Connection.environment(integration.id(), envName));
            }
        }
        return List.copyOf(connections);
    }

    public Option<Connection> activeConnection(Integration integration) {
        List<Connection> connections = connections(integration);
        return connections.isEmpty() ? Option.none() : Option.some(connections.getFirst());
    }

    /** The secret of the active connection, or empty when nothing is connected. */
    public Option<String> secret(Integration integration) {
        Option<Connection> connection = activeConnection(integration);
        if (connection.isEmpty()) return Option.none();
        Connection active = connection.orElseThrow();
        if (active.kind() == Connection.Kind.ENV) {
            String value = environment.apply(active.envName().orElseThrow());
            return Option.of(value == null || value.isBlank() ? null : value);
        }
        return active.credential().orElseThrow().secret();
    }

    public Credential create(CredentialStore.CredentialInput input) {
        CredentialStore target = requireStore();
        return target.create(input);
    }

    public void activate(String id) {
        requireStore().activate(id);
    }

    public void remove(String id) {
        requireStore().remove(id);
    }

    private CredentialStore requireStore() {
        if (store.isEmpty()) throw new Defect("no credential store configured");
        return store.orElseThrow();
    }

    private boolean isSet(String envName) {
        if (envName == null || envName.isBlank()) return false;
        String value = environment.apply(envName);
        return value != null && !value.isBlank();
    }
}
