package de.augmentia.oc.engine.runtime.observability;

/**
 * Thread-local correlation context for structured logging.
 *
 * <p>Each prompt gets a unique request ID and session ID that are propagated
 * to all log entries within that request's processing thread.</p>
 */
public final class CorrelationContext {

    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private CorrelationContext() {}

    /** Sets the correlation context for the current thread. */
    public static void set(String requestId, String sessionId) {
        if (requestId != null && !requestId.isBlank()) REQUEST_ID.set(requestId);
        if (sessionId != null && !sessionId.isBlank()) SESSION_ID.set(sessionId);
    }

    /** Returns the current request ID, or empty string if not set. */
    public static String requestId() {
        String v = REQUEST_ID.get();
        return v != null ? v : "";
    }

    /** Returns the current session ID, or empty string if not set. */
    public static String sessionId() {
        String v = SESSION_ID.get();
        return v != null ? v : "";
    }

    /** Clears the correlation context for the current thread. */
    public static void clear() {
        REQUEST_ID.remove();
        SESSION_ID.remove();
    }

    /** Returns a formatted correlation string for logging. */
    public static String format() {
        String rid = requestId();
        String sid = sessionId();
        if (rid.isEmpty() && sid.isEmpty()) return "";
        return "rid=" + rid + " sid=" + sid;
    }
}
