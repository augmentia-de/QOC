package de.augmentia.oc.engine.http;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Micrometer metrics for resilience events.
 */
@ApplicationScoped
public class ResilienceMetrics {

    @Inject
    MeterRegistry registry;

    public void preflightFailure() {
        registry.counter("resilience.preflight.failures").increment();
    }

    public void retry(String errorType) {
        registry.counter("resilience.retries.total", "error_type", errorType != null ? errorType : "unknown").increment();
    }

    public void loopDetected() {
        registry.counter("resilience.loops.detected").increment();
    }

    public void triageAttempt() {
        registry.counter("resilience.triage.attempts").increment();
    }

    public void webhookAlert() {
        registry.counter("resilience.webhook.alerts").increment();
    }

    public void stepTimeout() {
        registry.counter("resilience.step.timeouts").increment();
    }

    public Timer.Sample startTurnTimer() {
        return Timer.start(registry);
    }

    public void recordTurnDuration(Timer.Sample sample) {
        if (sample != null) sample.stop(registry.timer("engine.turn.duration"));
    }
}
