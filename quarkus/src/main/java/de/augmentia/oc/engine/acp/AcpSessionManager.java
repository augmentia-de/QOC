package de.augmentia.oc.engine.acp;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.http.Engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Maps ACP session IDs to QOC session IDs and tracks session state.
 */
public final class AcpSessionManager {

    private final Engine engine;
    private final Map<String, SessionId> sessions = new LinkedHashMap<>();

    public AcpSessionManager(Engine engine) {
        this.engine = engine;
    }

    /** Create a new QOC session for an ACP session/new request. */
    public String newSession(String agent, String directory) {
        String acpId = "acp-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // Use engine's session creation via the HTTP API indirectly.
            // For now, generate an ID and let the caller use run().
            SessionId qocId = SessionId.of(acpId);
            sessions.put(acpId, qocId);
            return acpId;
        } catch (Exception e) {
            return null;
        }
    }

    public SessionId qocSession(String acpId) {
        return sessions.get(acpId);
    }

    public boolean hasSession(String acpId) {
        return sessions.containsKey(acpId);
    }

    public void removeSession(String acpId) {
        sessions.remove(acpId);
    }

    public java.util.Set<String> sessionIds() {
        return java.util.Collections.unmodifiableSet(sessions.keySet());
    }
}