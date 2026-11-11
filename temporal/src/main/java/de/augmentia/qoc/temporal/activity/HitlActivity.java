package de.augmentia.qoc.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * HITL activities: start graph, resume graph.
 */
@ActivityInterface
public interface HitlActivity {
    @ActivityMethod
    HitlStart startGraph(String input, String threadId);

    @ActivityMethod
    String resumeGraph(String threadId);

    record HitlStart(String threadId, String plan, String result) {}
}
