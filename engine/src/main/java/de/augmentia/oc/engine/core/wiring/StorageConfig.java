package de.augmentia.oc.engine.core.wiring;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-port storage configuration.
 *
 * <p>Each port can override the global default kind:</p>
 * <pre>
 * engine.storage.default=postgres
 * engine.storage.journal=redis
 * engine.storage.tool-cache=memory
 * </pre>
 */
public final class StorageConfig {

    private final StorageKind defaultKind;
    private final Map<String, StorageKind> overrides;

    public StorageConfig(StorageKind defaultKind) {
        this(defaultKind, Map.of());
    }

    public StorageConfig(StorageKind defaultKind, Map<String, StorageKind> overrides) {
        this.defaultKind = defaultKind == null ? StorageKind.POSTGRES : defaultKind;
        this.overrides = overrides == null ? Map.of() : Map.copyOf(overrides);
    }

    public StorageKind defaultKind() {
        return defaultKind;
    }

    public StorageKind kindFor(String port, StorageKind fallback) {
        StorageKind override = overrides.get(port);
        if (override != null) return override;
        return fallback == null ? defaultKind : fallback;
    }

    public StorageKind kindFor(String port) {
        return kindFor(port, defaultKind);
    }

    /** Builder for programmatic configuration. */
    public static Builder builder(StorageKind defaultKind) {
        return new Builder(defaultKind);
    }

    public static final class Builder {
        private final StorageKind defaultKind;
        private final Map<String, StorageKind> overrides = new ConcurrentHashMap<>();

        private Builder(StorageKind defaultKind) {
            this.defaultKind = defaultKind == null ? StorageKind.POSTGRES : defaultKind;
        }

        public Builder override(String port, StorageKind kind) {
            if (port != null && !port.isBlank() && kind != null) {
                overrides.put(port.trim(), kind);
            }
            return this;
        }

        public StorageConfig build() {
            return new StorageConfig(defaultKind, overrides);
        }
    }
}
