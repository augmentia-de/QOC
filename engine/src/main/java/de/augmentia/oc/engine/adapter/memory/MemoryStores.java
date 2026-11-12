package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.wiring.StorageKind;
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

/**
 * Factory for the minimal in-memory storage set.
 *
 * <p>In-memory mode is intentionally minimal: only the state required for
 * direct execution (sessions, conversations, claims, journal, tool outputs) is
 * kept. Snapshots, approvals, instructions, credentials and the tool-result
 * cache are served by no-op adapters.</p>
 */
public final class MemoryStores {

    private MemoryStores() {}

    public static de.augmentia.oc.engine.core.wiring.Stores create(RuntimeState state) {
        return create(state, new de.augmentia.oc.engine.adapter.time.SystemClock(), null);
    }

    public static de.augmentia.oc.engine.core.wiring.Stores create(RuntimeState state, Clock clock,
                                                              FileSystem fileSystem) {
        RuntimeState s = state == null ? new RuntimeState() : state;
        Clock c = clock == null ? new de.augmentia.oc.engine.adapter.time.SystemClock() : clock;

        InMemorySessionStore sessionStore = new InMemorySessionStore(s);
        return new de.augmentia.oc.engine.core.wiring.Stores(
            sessionStore,                          // sessions
            sessionStore,                          // sessionReader (same)
            new InMemoryConversationStore(s),       // conversation
            new InMemoryJournal(s, c),             // journal
            new InMemoryClaimStore(s),             // claims
            new NoOpApprovalStore(),              // approvals (no-op)
            new NoOpInstructionStore(),           // instructions (no-op)
            sessionStore,                          // workspaces (same as sessions)
            new NoOpCredentialStore(),            // credentials (no-op)
            new NoOpSnapshotStore(),              // snapshots (no-op)
            new InMemoryToolOutputStore(s),        // toolOutputs (kept, LRU-capped)
            new NoOpToolResultCache()             // toolCache (no-op)
        );
    }
}