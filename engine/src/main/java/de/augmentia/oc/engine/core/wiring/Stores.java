package de.augmentia.oc.engine.core.wiring;

import de.augmentia.oc.engine.core.port.human.ApprovalSource;
import de.augmentia.oc.engine.core.port.human.NotificationChannel;
import de.augmentia.oc.engine.core.port.human.PolicySource;
import de.augmentia.oc.engine.core.port.human.QuestionSource;
import de.augmentia.oc.engine.core.port.runtime.Clock;
import de.augmentia.oc.engine.core.port.runtime.SessionWake;
import de.augmentia.oc.engine.core.port.store.ApprovalStore;
import de.augmentia.oc.engine.core.port.store.ClaimStore;
import de.augmentia.oc.engine.core.port.store.ConversationStore;
import de.augmentia.oc.engine.core.port.store.CredentialStore;
import de.augmentia.oc.engine.core.port.store.InstructionStore;
import de.augmentia.oc.engine.core.port.store.Journal;
import de.augmentia.oc.engine.core.port.store.SessionReader;
import de.augmentia.oc.engine.core.port.store.SessionStore;
import de.augmentia.oc.engine.core.port.store.SessionWorkspaceStore;
import de.augmentia.oc.engine.core.port.store.SnapshotStore;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;
import de.augmentia.oc.engine.core.port.store.ToolResultCache;

/**
 * Bundle of all storage and coordination ports.
 *
 * <p>Each port can use a different backend (memory, sqlite, postgres, redis),
 * enabling intermediate stages (e.g. durable sessions + in-memory tool cache).</p>
 */
public record Stores(
        SessionStore sessions,
        SessionReader sessionReader,
        ConversationStore conversation,
        Journal journal,
        ClaimStore claims,
        ApprovalStore approvals,
        InstructionStore instructions,
        SessionWorkspaceStore workspaces,
        CredentialStore credentials,
        SnapshotStore snapshots,
        ToolOutputStore toolOutputs,
        ToolResultCache toolCache) {

    public Stores {
        if (sessions == null) throw new IllegalArgumentException("sessions must not be null");
        if (sessionReader == null) sessionReader = sessions;
        if (conversation == null) throw new IllegalArgumentException("conversation must not be null");
        if (journal == null) throw new IllegalArgumentException("journal must not be null");
        if (claims == null) throw new IllegalArgumentException("claims must not be null");
        if (approvals == null) throw new IllegalArgumentException("approvals must not be null");
        if (instructions == null) throw new IllegalArgumentException("instructions must not be null");
        if (workspaces == null) throw new IllegalArgumentException("workspaces must not be null");
        if (credentials == null) throw new IllegalArgumentException("credentials must not be null");
        if (snapshots == null) throw new IllegalArgumentException("snapshots must not be null");
        if (toolOutputs == null) throw new IllegalArgumentException("toolOutputs must not be null");
        if (toolCache == null) throw new IllegalArgumentException("toolCache must not be null");
    }
}
