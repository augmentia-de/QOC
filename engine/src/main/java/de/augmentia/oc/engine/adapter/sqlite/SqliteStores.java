package de.augmentia.oc.engine.adapter.sqlite;

import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.world.FileSystem;

import javax.sql.DataSource;

/**
 * Factory for all SQLite storage adapters.
 */
public final class SqliteStores {

    private SqliteStores() {}

    public static Stores create(DataSource dataSource) {
        return create(dataSource, new de.augmentia.oc.engine.adapter.time.SystemClock(), null);
    }

    public static Stores create(DataSource dataSource, Clock clock, FileSystem fileSystem) {
        Clock c = clock == null ? new de.augmentia.oc.engine.adapter.time.SystemClock() : clock;
        FileSystem fs = fileSystem == null
                ? new de.augmentia.oc.engine.adapter.fs.NioFileSystem(java.nio.file.Path.of("."))
                : fileSystem;
        return new Stores(
            new SqliteSessions(dataSource),              // sessions
            new SqliteSessions(dataSource),              // sessionReader
            new SqliteConversation(dataSource),          // conversation
            new SqliteJournal(dataSource, c),            // journal
            new SqliteClaims(dataSource),                // claims
            new SqliteApprovals(dataSource),             // approvals
            new SqliteInstructions(dataSource),          // instructions
            new SqliteSessions(dataSource),              // workspaces
            new SqliteCredentials(dataSource),           // credentials
            new SqliteSnapshots(dataSource, fs),         // snapshots
            new SqliteToolOutput(dataSource, java.nio.file.Path.of("tool-output")),  // toolOutputs
            new SqliteToolResultCache(dataSource)        // toolCache
        );
    }
}
