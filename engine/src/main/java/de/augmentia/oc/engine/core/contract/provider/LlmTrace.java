package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;

import java.util.List;

/**
 * Optional sink for the raw LLM request/response exchange. Implementations
 * must not affect the model path: failures are swallowed by the adapter.
 */
public interface LlmTrace {

    /** The encoded request body that is sent to the provider. */
    void request(String endpoint, String body);

    /** The translated provider response (text, tool calls, usage, failures). */
    void response(int status, List<ModelEvent> events);

    /** A non-2xx response body, before it is classified. */
    void error(int status, String body);

    /** A transport failure before a response was available. */
    void transportFailure(String message);

    /** No-op trace used when logging is disabled. */
    static LlmTrace noop() {
        return Noop.INSTANCE;
    }

    /** No-op implementation. */
    final class Noop implements LlmTrace {
        private static final Noop INSTANCE = new Noop();

        private Noop() {}

        @Override
        public void request(String endpoint, String body) {}

        @Override
        public void response(int status, List<ModelEvent> events) {}

        @Override
        public void error(int status, String body) {}

        @Override
        public void transportFailure(String message) {}
    }
}
