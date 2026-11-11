package de.augmentia.qoc.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Metrics activities: run an agent and return metrics snapshot.
 */
@ActivityInterface
public interface MetricsActivity {
    @ActivityMethod
    String runWithMetrics(String input);
}
