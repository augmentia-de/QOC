package de.augmentia.qoc.temporal.activity;

import de.augmentia.oc.engine.runtime.QocEngine;

/**
 * Metrics activity: runs an agent via QocEngine and returns a simple metrics snapshot.
 */
public class MetricsActivityImpl implements MetricsActivity {

    private final QocEngine engine;

    public MetricsActivityImpl(QocEngine engine) {
        this.engine = engine;
    }

    @Override
    public String runWithMetrics(String input) {
        long start = System.currentTimeMillis();
        String result = engine.runPrompt("build", input).text();
        long duration = System.currentTimeMillis() - start;
        return "{\"result\":\"" + result.replace("\"", "\\\"") + "\","
                + "\"durationMs\":" + duration + "}";
    }
}
