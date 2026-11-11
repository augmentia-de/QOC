package de.augmentia.qoc.temporal.port;

/**
 * Port: Saga/Compensation — Forward-Steps + Kompensationen.
 */
public interface SagaPort {
    void start(String sagaId);
    void addStep(String sagaId, String stepName, Runnable forward, Runnable compensate);
    void compensate(String sagaId);
}
