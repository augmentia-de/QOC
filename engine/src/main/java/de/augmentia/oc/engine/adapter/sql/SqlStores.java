package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.world.FileSystem;

import javax.sql.DataSource;

/**
 * Factory for all PostgreSQL storage adapters.
 */
public final class SqlStores {

    private SqlStores() {}

    public static Stores create(DataSource dataSource) {
        return create(dataSource, new de.augmentia.oc.engine.adapter.time.SystemClock(), null);
    }

    public static Stores create(DataSource dataSource, Clock clock, FileSystem fileSystem) {
        Clock c = clock == null ? new de.augmentia.oc.engine.adapter.time.SystemClock() : clock;
        FileSystem fs = fileSystem == null ? null : fileSystem;
        // FileSystem is needed for snapshots; if null, use a no-op placeholder
        if (fs == null) {
            fs = new de.augmentia.oc.engine.adapter.fs.NioFileSystem(java.nio.file.Path.of("."));
        }
        return new Stores(
            new SqlSessions(dataSource),              // sessions
            new SqlSessions(dataSource),              // sessionReader
            new SqlConversation(dataSource),          // conversation
            new SqlJournal(dataSource, c),           // journal
            new SqlClaims(dataSource),                // claims
            new SqlApprovals(dataSource),             // approvals
            new SqlInstructions(dataSource),          // instructions
            new SqlSessions(dataSource),              // workspaces
            new SqlCredentials(dataSource),           // credentials
            new SqlSnapshots(dataSource, fs),         // snapshots
            new SqlToolOutput(dataSource, java.nio.file.Path.of("tool-output")),  // toolOutputs
            new SqlToolResultCache(dataSource)        // toolCache
        );
    }
}
