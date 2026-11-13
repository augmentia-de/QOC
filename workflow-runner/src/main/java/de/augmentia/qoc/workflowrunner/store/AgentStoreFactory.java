package de.augmentia.qoc.workflowrunner.store;

import javax.sql.DataSource;
import java.util.Locale;

/**
 * Selects the agent specification backend from configuration (SSOT).
 */
public final class AgentStoreFactory {

    private AgentStoreFactory() {}

    public static AgentStorePort create(String configuredKind,
                                        String engineStorage,
                                        String databaseKind,
                                        String storageDir,
                                        DataSource dataSource) {
        String kind = resolve(configuredKind, engineStorage, databaseKind);
        return switch (kind) {
            case "memory" -> new InMemoryAgentStore();
            case "sqlite" -> new SqliteAgentStore(require(dataSource, "sqlite"));
            case "postgres" -> new PostgresAgentStore(require(dataSource, "postgres"));
            case "file" -> new AgentStore(java.nio.file.Path.of(storageDir));
            default -> throw new IllegalArgumentException("Unknown agent storage kind: " + kind);
        };
    }

    public static String resolve(String configuredKind, String engineStorage, String databaseKind) {
        String configured = normalize(configuredKind);
        if (!configured.isEmpty() && !"auto".equals(configured)) {
            return configured;
        }
        String engine = normalize(engineStorage);
        if ("memory".equals(engine)) return "memory";
        String database = normalize(databaseKind);
        if ("sqlite".equals(engine) || "sqlite".equals(database)) return "sqlite";
        if ("postgres".equals(engine) || "postgresql".equals(database) || "postgres".equals(database)) {
            return "postgres";
        }
        return "file";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static DataSource require(DataSource dataSource, String kind) {
        if (dataSource == null) {
            throw new IllegalStateException("Agent storage '" + kind + "' requires an active datasource");
        }
        return dataSource;
    }
}