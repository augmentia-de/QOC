package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.StoredEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

    @Test
    void subscribersReceiveOnlyTheirAggregateAndCanUnsubscribe() {
        EventBus bus = new EventBus();
        SessionId first = SessionId.create();
        SessionId second = SessionId.create();
        List<StoredEvent> received = new ArrayList<>();

        Runnable unsubscribe = bus.subscribe(first.value(), received::add);
        bus.publish(event(first, 1));
        bus.publish(event(second, 1));
        unsubscribe.run();
        bus.publish(event(first, 2));

        assertThat(received).hasSize(1);
        assertThat(received.getFirst().seq()).isEqualTo(Seq.of(1));
    }

    private static StoredEvent event(SessionId session, long seq) {
        return new StoredEvent(session.value(), Seq.of(seq), "ExecutionStarted", 1,
                new SessionEvent.ExecutionStarted(session), Instant.EPOCH);
    }
}
