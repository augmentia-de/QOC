package de.augmentia.oc.engine.core.model;

/** Marker for an event payload. {@link SessionEvent} is the durable variant. */
public sealed interface Payload permits SessionEvent {
}