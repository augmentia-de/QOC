package de.augmentia.oc.engine.runtime.credential;

import de.augmentia.oc.engine.core.model.Credential;
import de.augmentia.oc.engine.core.model.Option;

/**
 * A concrete way to authenticate. Stored credentials are represented by the
 * credential; environment connections carry only the variable name.
 */
public record Connection(String integrationId, Kind kind, Option<Credential> credential,
                         Option<String> envName) {

    public enum Kind {
        STORED, ENV
    }

    public Connection {
        if (integrationId == null || integrationId.isBlank()) {
            throw new IllegalArgumentException("integrationId must not be blank");
        }
        if (kind == null) throw new IllegalArgumentException("kind must not be null");
        if (credential == null) credential = Option.none();
        if (envName == null) envName = Option.none();
    }

    public static Connection stored(String integrationId, Credential credential) {
        return new Connection(integrationId, Kind.STORED, Option.some(credential), Option.none());
    }

    public static Connection environment(String integrationId, String envName) {
        return new Connection(integrationId, Kind.ENV, Option.none(), Option.some(envName));
    }
}
