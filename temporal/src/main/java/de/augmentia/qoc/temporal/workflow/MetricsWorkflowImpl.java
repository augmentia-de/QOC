package de.augmentia.qoc.temporal.workflow;

import de.augmentia.qoc.temporal.activity.MetricsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Metrics workflow: runs an agent and returns metrics snapshot.
 */
public class MetricsWorkflowImpl implements MetricsWorkflow {

    private final MetricsActivity activities = Workflow.newActivityStub(
            MetricsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    @Override
    public String run(String input) {
        return activities.runWithMetrics(input);
    }
}
