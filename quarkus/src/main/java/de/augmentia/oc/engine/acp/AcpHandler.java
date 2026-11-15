package de.augmentia.oc.engine.acp;

import de.augmentia.oc.engine.core.model.EngineResult;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.http.Engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registers ACP method handlers on an AcpServer, backed by QOC's Engine.
 * Covers session/new, session/prompt, session/cancel, session/load, session/list, session/delete.
 */
public final class AcpHandler {

    private final Engine engine;
    private final AcpSessionManager sessions;
    private final AcpTransport transport;

    public AcpHandler(Engine engine, AcpSessionManager sessions, AcpTransport transport) {
        this.engine = engine;
        this.sessions = sessions;
        this.transport = transport;
    }

    public void register(AcpServer server) {
        server.register("session/new", this::sessionNew);
        server.register("session/prompt", this::sessionPrompt);
        server.register("session/cancel", this::sessionCancel);
        server.register("session/load", this::sessionLoad);
        server.register("session/list", this::sessionList);
        server.register("session/delete", this::sessionDelete);
        server.register("session/close", this::sessionClose);
        server.register("session/set_config_option", this::setConfigOption);
        server.register("session/set_mode", this::setMode);
        server.register("authenticate", this::authenticate);
    }

    private Map<String, Object> sessionNew(Map<String, Object> params) {
        String agent = params.get("agent") instanceof String s ? s : null;
        String cwd = params.get("cwd") instanceof String s ? s : null;
        String acpId = sessions.newSession(agent, cwd);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", acpId);
        return result;
    }

    private Map<String, Object> sessionPrompt(Map<String, Object> params) {
        String sessionId = params.get("sessionId") instanceof String s ? s : null;
        String text = params.get("text") instanceof String s ? s : "";
        if (sessionId == null || !sessions.hasSession(sessionId)) {
            throw new IllegalArgumentException("unknown session: " + sessionId);
        }
        // Run prompt synchronously and send result as session/update notification.
        try {
            EngineResult result = engine.qocEngine().runPrompt(
                    null, text);
            String responseText = result == null ? "" : result.text();
            // Send session/update notification.
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("sessionId", sessionId);
            update.put("update", Map.of("type", "text", "text", responseText));
            Map<String, Object> notif = new LinkedHashMap<>();
            notif.put("jsonrpc", "2.0");
            notif.put("method", "session/update");
            notif.put("params", update);
            try { transport.writeMessage(notif); } catch (Exception ignored) {}
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("stopReason", "end_turn");
            return resp;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private Map<String, Object> sessionCancel(Map<String, Object> params) {
        // Best-effort cancel — QOC abort is per-session.
        return Map.of("cancelled", true);
    }

    private Map<String, Object> sessionLoad(Map<String, Object> params) {
        String sessionId = params.get("sessionId") instanceof String s ? s : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("exists", sessions.hasSession(sessionId));
        return result;
    }

    private Map<String, Object> sessionList(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessions", sessions.sessionIds());
        return result;
    }

    private Map<String, Object> sessionDelete(Map<String, Object> params) {
        String sessionId = params.get("sessionId") instanceof String s ? s : null;
        sessions.removeSession(sessionId);
        return Map.of("deleted", true);
    }

    private Map<String, Object> sessionClose(Map<String, Object> params) {
        String sessionId = params.get("sessionId") instanceof String s ? s : null;
        sessions.removeSession(sessionId);
        return Map.of("closed", true);
    }

    private Map<String, Object> setConfigOption(Map<String, Object> params) {
        return Map.of("applied", true);
    }

    private Map<String, Object> setMode(Map<String, Object> params) {
        return Map.of("mode", params.get("mode"));
    }

    private Map<String, Object> authenticate(Map<String, Object> params) {
        return Map.of("authenticated", true);
    }
}