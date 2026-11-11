package de.augmentia.qoc.temporal.activity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saga activity implementation (side-effecting steps).
 */
public class SagaActivityImpl implements SagaActivity {

    private static final Logger log = LoggerFactory.getLogger(SagaActivityImpl.class);

    @Override
    public String reserve(String orderId) {
        log.info("[saga] reserve order={}", orderId);
        return "res-" + orderId;
    }

    @Override
    public String charge(String orderId, double amount) {
        if (amount > 100.0) {
            throw new IllegalStateException("payment declined for order " + orderId);
        }
        log.info("[saga] charge order={} amount={}", orderId, amount);
        return "charged:" + orderId;
    }

    @Override
    public void release(String reservationId) {
        log.warn("[saga] COMPENSATE release reservation={}", reservationId);
    }

    @Override
    public void refund(String orderId) {
        log.warn("[saga] COMPENSATE refund order={}", orderId);
    }
}
