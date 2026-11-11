package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.adapter.sql.SqlApprovals;
import de.augmentia.oc.engine.adapter.sql.SqlClaims;
import de.augmentia.oc.engine.adapter.sql.SqlConversation;
import de.augmentia.oc.engine.adapter.sql.SqlCredentials;
import de.augmentia.oc.engine.adapter.sql.SqlInstructions;
import de.augmentia.oc.engine.adapter.sql.SqlJournal;
import de.augmentia.oc.engine.adapter.sql.SqlSessions;
import de.augmentia.oc.engine.adapter.sql.SqlSnapshots;
import de.augmentia.oc.engine.adapter.sql.SqlToolOutput;
import de.augmentia.oc.engine.adapter.sql.SqlToolResultCache;
import de.augmentia.oc.engine.adapter.sqlite.SqliteApprovals;
import de.augmentia.oc.engine.adapter.sqlite.SqliteClaims;
import de.augmentia.oc.engine.adapter.sqlite.SqliteConversation;
import de.augmentia.oc.engine.adapter.sqlite.SqliteCredentials;
import de.augmentia.oc.engine.adapter.sqlite.SqliteInstructions;
import de.augmentia.oc.engine.adapter.sqlite.SqliteJournal;
import de.augmentia.oc.engine.adapter.sqlite.SqliteSessions;
import de.augmentia.oc.engine.adapter.sqlite.SqliteSnapshots;
import de.augmentia.oc.engine.adapter.sqlite.SqliteToolOutput;
import de.augmentia.oc.engine.adapter.sqlite.SqliteToolResultCache;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.store.ApprovalStore;
import de.augmentia.oc.engine.core.port.store.ClaimStore;
import de.augmentia.oc.engine.core.port.store.ConversationStore;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.core.port.store.InstructionStore;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.core.port.store.SessionStore;
import de.augmentia.oc.engine.core.port.store.SessionWorkspaceStore;
import de.augmentia.oc.engine.core.port.store.SnapshotStore;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;
import de.augmentia.oc.engine.core.port.store.ToolResultCache;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;

import javax.sql.DataSource;

/**
 * Single switch point for storage backends.
 *
 * <p>Each port is created independently based on its configured {@link StorageKind},
 * enabling intermediate stages (e.g. sessions in Postgres, tool-cache in memory).</p>
 *
 * <p>The only place that knows about backend types. {@code Engine}, {@code Coordinator},
 * {@code Step} and all runtime classes remain backend-agnostic.</p>
 */
public final class StorageFactory {

    private StorageFactory() {}

    public static Stores create(StorageConfig config, RuntimeState memory,
                                DataSource dataSource, Clock clock, FileSystem fileSystem) {
        StorageKind defaultKind = config == null ? StorageKind.POSTGRES : config.defaultKind();

        return new Stores(
            sessionStore(kind(config, "sessions", defaultKind), memory, dataSource, clock),
            sessionReader(kind(config, "sessions", defaultKind), memory, dataSource, clock),
            conversationStore(kind(config, "conversation", defaultKind), memory, dataSource),
            journal(kind(config, "journal", defaultKind), memory, dataSource, clock),
            claimStore(kind(config, "claims", defaultKind), memory, dataSource),
            approvalStore(kind(config, "approvals", defaultKind), memory, dataSource),
            instructionStore(kind(config, "instructions", defaultKind), memory, dataSource),
            workspaceStore(kind(config, "workspaces", defaultKind), memory, dataSource),
            credentialStore(kind(config, "credentials", defaultKind), memory, dataSource),
            snapshotStore(kind(config, "snapshots", defaultKind), memory, dataSource, fileSystem),
            toolOutputStore(kind(config, "tool-outputs", defaultKind), memory, dataSource),
            toolResultCache(kind(config, "tool-cache", defaultKind), memory, dataSource)
        );
    }

    // --- per-port factory methods ---

    private static StorageKind kind(StorageConfig config, String port, StorageKind defaultKind) {
        return config == null ? defaultKind : config.kindFor(port, defaultKind);
    }

    private static SessionStore sessionStore(StorageKind kind, RuntimeState memory,
                                            DataSource dataSource, Clock clock) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemorySessionStore(memory);
            case SQLITE -> new SqliteSessions(dataSource);
            case POSTGRES -> new SqlSessions(dataSource);
        };
    }

    private static de.augmentia.oc.engine.core.port.store.SessionReader sessionReader(
            StorageKind kind, RuntimeState memory, DataSource dataSource, Clock clock) {
        return (de.augmentia.oc.engine.core.port.store.SessionReader) sessionStore(kind, memory, dataSource, clock);
    }

    private static ConversationStore conversationStore(StorageKind kind, RuntimeState memory,
                                                      DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemoryConversationStore(memory);
            case SQLITE -> new SqliteConversation(dataSource);
            case POSTGRES -> new SqlConversation(dataSource);
        };
    }

    private static Journal journal(StorageKind kind, RuntimeState memory,
                                  DataSource dataSource, Clock clock) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemoryJournal(memory, clock);
            case SQLITE -> new SqliteJournal(dataSource, clock);
            case POSTGRES -> new SqlJournal(dataSource, clock);
        };
    }

    private static ClaimStore claimStore(StorageKind kind, RuntimeState memory,
                                        DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemoryClaimStore(memory);
            case SQLITE -> new SqliteClaims(dataSource);
            case POSTGRES -> new SqlClaims(dataSource);
        };
    }

    private static ApprovalStore approvalStore(StorageKind kind, RuntimeState memory,
                                              DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemoryApprovalStore(memory);
            case SQLITE -> new SqliteApprovals(dataSource);
            case POSTGRES -> new SqlApprovals(dataSource);
        };
    }

    private static InstructionStore instructionStore(StorageKind kind, RuntimeState memory,
                                                    DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemoryInstructionStore(memory);
            case SQLITE -> new SqliteInstructions(dataSource);
            case POSTGRES -> new SqlInstructions(dataSource);
        };
    }

    private static SessionWorkspaceStore workspaceStore(StorageKind kind, RuntimeState memory,
                                                      DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemorySessionStore(memory);
            case SQLITE -> new SqliteSessions(dataSource);
            case POSTGRES -> new SqlSessions(dataSource);
        };
    }

    private static CredentialStore credentialStore(StorageKind kind, RuntimeState memory,
                                                  DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemoryCredentialStore(memory);
            case SQLITE -> new SqliteCredentials(dataSource);
            case POSTGRES -> new SqlCredentials(dataSource);
        };
    }

    private static SnapshotStore snapshotStore(StorageKind kind, RuntimeState memory,
                                             DataSource dataSource, FileSystem fileSystem) {
        FileSystem fs = fileSystem == null
                ? new de.augmentia.oc.engine.adapter.fs.NioFileSystem(java.nio.file.Path.of("."))
                : fileSystem;
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemorySnapshotStore(memory);
            case SQLITE -> new SqliteSnapshots(dataSource, fs);
            case POSTGRES -> new SqlSnapshots(dataSource, fs);
        };
    }

    private static ToolOutputStore toolOutputStore(StorageKind kind, RuntimeState memory,
                                                  DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemoryToolOutputStore(memory);
            case SQLITE -> new SqliteToolOutput(dataSource, java.nio.file.Path.of("tool-output"));
            case POSTGRES -> new SqlToolOutput(dataSource, java.nio.file.Path.of("tool-output"));
        };
    }

    private static ToolResultCache toolResultCache(StorageKind kind, RuntimeState memory,
                                                  DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> new de.augmentia.oc.engine.adapter.memory.InMemoryToolResultCache(memory);
            case SQLITE -> new SqliteToolResultCache(dataSource);
            case POSTGRES -> new SqlToolResultCache(dataSource);
        };
    }
}
