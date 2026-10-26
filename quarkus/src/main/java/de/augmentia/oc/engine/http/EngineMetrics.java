package de.augmentia.oc.engine.http;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal process-local operational counters exposed at /api/metrics. */
@ApplicationScoped
public class EngineMetrics {

    private final AtomicLong prompts = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong busy = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong compactions = new AtomicLong();

    public void promptStarted() { prompts.incrementAndGet(); }
    public void promptCompleted() { completed.incrementAndGet(); }
    public void promptBusy() { busy.incrementAndGet(); }
    public void promptFailed() { failed.incrementAndGet(); }
    public void promptCancelled() { cancelled.incrementAndGet(); }
    public void toolCalls(int count) { toolCalls.addAndGet(count); }
    public void compaction() { compactions.incrementAndGet(); }

    public Map<String, Long> snapshot() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("prompts", prompts.get());
        values.put("completed", completed.get());
        values.put("busy", busy.get());
        values.put("failed", failed.get());
        values.put("cancelled", cancelled.get());
        values.put("toolCalls", toolCalls.get());
        values.put("compactions", compactions.get());
        return values;
    }
}
