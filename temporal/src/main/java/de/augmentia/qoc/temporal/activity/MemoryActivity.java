package de.augmentia.qoc.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Memory activities: remember/recall facts.
 */
@ActivityInterface
public interface MemoryActivity {
    @ActivityMethod
    String remember(String scope, String content);

    @ActivityMethod
    String recall(String scope);
}
