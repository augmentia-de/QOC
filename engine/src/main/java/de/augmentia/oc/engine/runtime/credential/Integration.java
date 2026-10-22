package de.augmentia.oc.engine.runtime.credential;

import java.util.List;

/** An external integration and the environment variables that may carry its key. */
public record Integration(String id, List<String> envNames) {
    public Integration {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        envNames = envNames == null ? List.of() : List.copyOf(envNames);
    }

    public static Integration of(String id, String... envNames) {
        return new Integration(id, List.of(envNames));
    }
}
