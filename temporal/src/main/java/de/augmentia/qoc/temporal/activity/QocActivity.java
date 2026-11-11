package de.augmentia.qoc.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Activity that runs a QOC agent prompt.
 */
@ActivityInterface
public interface QocActivity {

    @ActivityMethod
    String runPrompt(String agentName, String prompt);
}
