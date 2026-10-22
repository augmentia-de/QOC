package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;

import java.util.List;

/** Translates one SSE frame into zero or more normalized model events. */
@FunctionalInterface
public interface EventTranslator {

    List<ModelEvent> translate(SseReader.SseFrame frame);
}
