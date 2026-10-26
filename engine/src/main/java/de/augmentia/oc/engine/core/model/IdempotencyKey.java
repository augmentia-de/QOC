package de.augmentia.oc.engine.core.model;

/**
 * Stable correlation token for one tool call. It is created when the call is
 * announced and reused by approval, execution and recovery. QOC still
 * guarantees at-least-once execution; the key lets side-effecting tools
 * deduplicate.
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key must not be blank");
        }
    }

    public static IdempotencyKey of(SessionId session, CallId call) {
        if (session == null || call == null) {
            throw new IllegalArgumentException("session and call must not be null");
        }
        return new IdempotencyKey("idem_" + session.value() + "_" + call.value());
    }
}
