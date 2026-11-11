package de.augmentia.qoc.temporal.workflow;

import de.augmentia.qoc.temporal.activity.MemoryActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Memory workflow: remember or recall facts per scope.
 */
public class MemoryWorkflowImpl implements MemoryWorkflow {

    private final MemoryActivity activities = Workflow.newActivityStub(
            MemoryActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    @Override
    public String run(String op, String scope, String content) {
        if ("recall".equalsIgnoreCase(op)) {
            return activities.recall(scope);
        }
        return activities.remember(scope, content);
    }
}
