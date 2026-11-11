package de.augmentia.qoc.temporal.workflow;

import de.augmentia.qoc.temporal.activity.SagaActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Saga implementation with explicit compensating actions on failure.
 */
public class SagaWorkflowImpl implements SagaWorkflow {

    private final SagaActivity activities = Workflow.newActivityStub(
            SagaActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    @Override
    public String run(String orderId, double amount) {
        String reservation = activities.reserve(orderId);
        try {
            return "completed: " + activities.charge(orderId, amount);
        } catch (ActivityFailure failure) {
            activities.release(reservation);
            activities.refund(orderId);
            return "compensated (payment failed) — released=" + reservation;
        }
    }
}
