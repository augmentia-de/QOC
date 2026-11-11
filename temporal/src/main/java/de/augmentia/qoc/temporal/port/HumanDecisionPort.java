package de.augmentia.qoc.temporal.port;

/**
 * Port: Human-in-the-Loop — Pause + Signal für Freigaben.
 * Implementierung: Temporal Signal channel.
 */
public interface HumanDecisionPort {
    void pause(String runId, String reason);
    void decide(String runId, boolean approved);
    boolean isPaused(String runId);
}
