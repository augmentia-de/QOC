package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.runtime.SessionWake;
import de.augmentia.oc.engine.testing.CountingWake;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of {@link SessionWake}. */
class SessionWakeContract {

    @Test
    void wakeCountsCallsPerSession() {
        CountingWake wake = new CountingWake();
        SessionId first = SessionId.create();
        SessionId second = SessionId.create();

        wake.wake(first);
        wake.wake(first);
        wake.wake(second);

        assertThat(wake.count()).isEqualTo(3);
        assertThat(wake.sessions()).containsExactly(first, first, second);
    }

    @Test
    void noWakeMeansZero() {
        assertThat(new CountingWake().count()).isZero();
    }
}
