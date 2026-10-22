package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.StoredEvent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-process live feed. The SSE endpoint subscribes before replaying history. */
@ApplicationScoped
public class EventBus {

    private final Map<String, List<Consumer<StoredEvent>>> listeners = new ConcurrentHashMap<>();

    public Runnable subscribe(String aggregateId, Consumer<StoredEvent> listener) {
        if (aggregateId == null || listener == null) {
            throw new IllegalArgumentException("aggregateId and listener must not be null");
        }
        List<Consumer<StoredEvent>> registered =
                listeners.computeIfAbsent(aggregateId, key -> new CopyOnWriteArrayList<>());
        registered.add(listener);
        return () -> {
            registered.remove(listener);
            if (registered.isEmpty()) listeners.remove(aggregateId, registered);
        };
    }

    public void publish(StoredEvent event) {
        if (event == null) return;
        for (Consumer<StoredEvent> listener : listeners.getOrDefault(event.aggregateId(), List.of())) {
            listener.accept(event);
        }
    }
}
