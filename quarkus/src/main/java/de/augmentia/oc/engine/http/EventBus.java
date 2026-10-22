package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.StoredEvent;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-process live feed. The SSE endpoint subscribes before replaying history. */
@ApplicationScoped
public class EventBus {

    private final Map<String, List<Consumer<StoredEvent>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> live = new ConcurrentHashMap<>();

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

    public Runnable subscribeLive(String aggregateId, Consumer<String> listener) {
        if (aggregateId == null || listener == null) {
            throw new IllegalArgumentException("aggregateId and listener must not be null");
        }
        List<Consumer<String>> registered = live.computeIfAbsent(aggregateId, key -> new CopyOnWriteArrayList<>());
        registered.add(listener);
        return () -> {
            registered.remove(listener);
            if (registered.isEmpty()) live.remove(aggregateId, registered);
        };
    }

    public void publishLive(String aggregateId, String json) {
        if (aggregateId == null || json == null) return;
        for (Consumer<String> listener : live.getOrDefault(aggregateId, List.of())) {
            listener.accept(json);
        }
    }
}
