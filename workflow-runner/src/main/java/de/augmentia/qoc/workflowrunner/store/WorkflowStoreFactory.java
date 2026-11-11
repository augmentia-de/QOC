package de.augmentia.qoc.workflowrunner.store;

import javax.sql.DataSource;
import java.util.Locale;

/**
 * Selects the workflow specification backend from configuration.
 *
 * <p>{@code auto} follows the engine storage kind so the memory profile never
 * touches a database and the durable profiles use the same backend as the
 * engine journal.</p>
 */
public final class WorkflowStoreFactory {

    private WorkflowStoreFactory() {}

    public static WorkflowDefinitionStore create(String configuredKind,
                                                 String engineStorage,
                                                 String databaseKind,
                                                 String storageDir,
                                                 DataSource dataSource) {
        String kind = resolve(configuredKind, engineStorage, databaseKind);
        return switch (kind) {
            case "memory" -> new InMemoryWorkflowStore();
            case "sqlite" -> new SqliteWorkflowStore(require(dataSource, "sqlite"));
            case "postgres" -> new PostgresWorkflowStore(require(dataSource, "postgres"));
            case "file" -> new WorkflowStore(storageDir);
            default -> throw new IllegalArgumentException("Unknown workflow storage kind: " + kind);
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
            throw new IllegalStateException("Workflow storage '" + kind + "' requires an active datasource");
        }
        return dataSource;
    }
}
