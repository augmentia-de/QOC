package de.augmentia.oc.engine.runtime.callback;

/**
 * Callback for publishing events to a UI or external system.
 * Framework-neutral: Quarkus implements via EventBus, default is no-op.
 */
public interface EventPublisher {
    void publishLive(String sessionId, String json);

    default void publishToolResult(String sessionId, String toolCallId, String resultText) {
        // no-op default
    }

    static EventPublisher noop() {
        return new EventPublisher() {
            public void publishLive(String sessionId, String json) { /* no-op */ }
            public void publishToolResult(String sessionId, String toolCallId, String resultText) { /* no-op */ }
        };
    }
}