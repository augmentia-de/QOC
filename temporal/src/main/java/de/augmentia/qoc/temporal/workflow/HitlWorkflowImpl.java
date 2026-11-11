package de.augmentia.qoc.temporal.workflow;

import de.augmentia.qoc.temporal.activity.HitlActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * HITL workflow implementation: runs prompt, waits for approval signal, resumes.
 */
public class HitlWorkflowImpl implements HitlWorkflow {

    private final HitlActivity activity = Workflow.newActivityStub(
            HitlActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    private Boolean approved;

    @Override
    public String run(String input) {
        String threadId = Workflow.getInfo().getWorkflowId();
        HitlActivity.HitlStart start = activity.startGraph(input, threadId);

        Workflow.await(() -> approved != null);

        if (Boolean.TRUE.equals(approved)) {
            return activity.resumeGraph(threadId);
        }
        return "rejected by human (plan=" + start.plan() + ")";
    }

    @Override
    public void decide(boolean approved) {
        this.approved = approved;
    }
}
