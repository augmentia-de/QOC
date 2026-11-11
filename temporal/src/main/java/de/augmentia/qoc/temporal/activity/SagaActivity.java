package de.augmentia.qoc.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Saga activities: reserve, charge, release, refund.
 */
@ActivityInterface
public interface SagaActivity {
    @ActivityMethod
    String reserve(String orderId);

    @ActivityMethod
    String charge(String orderId, double amount);

    @ActivityMethod
    void release(String reservationId);

    @ActivityMethod
    void refund(String orderId);
}
