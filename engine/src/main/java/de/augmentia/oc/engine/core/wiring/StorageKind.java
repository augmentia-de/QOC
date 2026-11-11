package de.augmentia.oc.engine.core.wiring;

/**
 * Storage backend kinds supported by QOC.
 *
 * <p>Each {@link Stores} bundle can use a different kind per port, enabling
 * intermediate stages (e.g. sessions in Postgres, tool-cache in memory).</p>
 */
public enum StorageKind {
    MEMORY,
    SQLITE,
    POSTGRES;

    public static StorageKind from(String value) {
        if (value == null || value.isBlank()) return POSTGRES;
        try {
            return StorageKind.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return POSTGRES;
        }
    }
}
