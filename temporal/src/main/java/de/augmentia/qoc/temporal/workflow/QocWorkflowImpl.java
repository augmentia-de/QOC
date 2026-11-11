package de.augmentia.qoc.temporal.workflow;

import de.augmentia.qoc.temporal.activity.QocActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

/**
 * Workflow implementation: one step that delegates to QocActivity.
 */
public class QocWorkflowImpl implements QocWorkflow {

    private final QocActivity activity = Workflow.newActivityStub(
            QocActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .build());

    @Override
    public String run(String agentName, String prompt) {
        return activity.runPrompt(agentName, prompt);
    }
}
