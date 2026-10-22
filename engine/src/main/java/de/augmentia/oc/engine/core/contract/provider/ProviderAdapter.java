package de.augmentia.oc.engine.core.contract.provider;

import java.util.stream.Stream;

@FunctionalInterface
public interface ProviderAdapter {
    Stream<ModelEvent> stream(ModelRequest request);
}