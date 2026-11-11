package de.augmentia.oc.engine.core.contract.provider;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

@FunctionalInterface
public interface ProviderAdapter {
    Stream<ModelEvent> stream(ModelRequest request);

    default Stream<ModelEvent> stream(ModelRequest request, Consumer<ModelEvent> sink) {
        return stream(request, sink, 0, null);
    }

    default Stream<ModelEvent> stream(ModelRequest request, Consumer<ModelEvent> sink, int callIndex) {
        return stream(request, sink, callIndex, null);
    }

    default Stream<ModelEvent> stream(ModelRequest request, Consumer<ModelEvent> sink, int callIndex,
                                     AtomicReference<String> summary) {
        return stream(request);
    }
}
