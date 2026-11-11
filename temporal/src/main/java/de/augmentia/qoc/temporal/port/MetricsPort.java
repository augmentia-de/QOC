package de.augmentia.qoc.temporal.port;

/**
 * Port: Metrics/OTel — Counter, Timer, Traces.
 */
public interface MetricsPort {
    void recordLlmCall(String agent, int inputTokens, int outputTokens, long durationMs);
    void recordToolExecution(String tool, long durationMs, boolean success);
    void recordError(String agent, String error);
}
