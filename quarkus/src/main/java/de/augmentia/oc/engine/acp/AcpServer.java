package de.augmentia.oc.engine.acp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * JSON-RPC 2.0 dispatcher for ACP. Reads messages from transport, dispatches
 * to registered method handlers, writes responses.
 */
public final class AcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION = "1.0.0";

    private final AcpTransport transport;
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> handlers = new LinkedHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public AcpServer(AcpTransport transport) {
        this.transport = transport;
        // Register built-in handlers.
        handlers.put("initialize", this::handleInitialize);
    }

    /** Register a method handler. */
    public void register(String method, Function<Map<String, Object>, Map<String, Object>> handler) {
        handlers.put(method, handler);
    }

    /** Run the main read-dispatch-write loop (blocking). */
    public void run() {
        while (running.get()) {
            Map<String, Object> msg;
            try {
                msg = transport.readMessage();
            } catch (IOException e) {
                break;
            }
            if (msg == null) break; // EOF
            dispatch(msg);
        }
    }

    public void stop() {
        running.set(false);
    }

    @SuppressWarnings("unchecked")
    private void dispatch(Map<String, Object> msg) {
        Object id = msg.get("id");
        String method = (String) msg.get("method");
        Map<String, Object> params = msg.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        if (method == null) {
            // Response or notification without method — ignore.
            return;
        }

        Function<Map<String, Object>, Map<String, Object>> handler = handlers.get(method);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        if (id != null) response.put("id", id);

        if (handler == null) {
            response.put("error", Map.of("code", -32601, "message", "Method not found: " + method));
        } else {
            try {
                Map<String, Object> result = handler.apply(params);
                if (result != null) response.put("result", result);
            } catch (Exception e) {
                response.put("error", Map.of("code", -32603, "message", e.getMessage() == null ? "internal error" : e.getMessage()));
            }
        }

        if (id != null) {
            try {
                transport.writeMessage(response);
            } catch (IOException ignored) {}
        }
    }

    private Map<String, Object> handleInitialize(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", 1);
        result.put("agentInfo", Map.of("name", "qoc", "version", VERSION));
        result.put("agentCapabilities", Map.of(
                "session", Map.of("list", true, "load", true, "resume", true, "fork", true),
                "prompt", Map.of("text", true, "image", false),
                "tools", Map.of("execute", true)
        ));
        result.put("authMethods", java.util.List.of());
        return result;
    }
}