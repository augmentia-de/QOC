package de.augmentia.oc.engine.core.contract.provider;

/**
 * Closed failure family at the provider boundary. {@link FailureKind} names
 * the concrete classes; the message is never part of the decision.
 */
public sealed interface ProviderFailure permits FailureKind {
}
