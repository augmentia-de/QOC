package de.augmentia.oc.engine.runtime.callback;

/**
 * Optional tracer provider for OpenTelemetry-style tracing.
 * Framework-neutral: Quarkus implements via OTel Tracer, default is no-op.
 */
public interface TracerProvider {
    Object startSpan(String name, java.util.Map<String, String> attributes);

    default void endSpan(Object span) { /* no-op */ }

    static TracerProvider noop() {
        return new TracerProvider() {
            public Object startSpan(String name, java.util.Map<String, String> attributes) { return null; }
            public void endSpan(Object span) { /* no-op */ }
        };
    }
}