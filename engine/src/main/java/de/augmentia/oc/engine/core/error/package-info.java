/**
 * S0 error channels: {@code Defect} (invariant violation), {@code Aborted}
 * (deliberate stop) and {@code StoreFailure} (persistence problem).
 *
 * <p>The three channels stay separate; an error is never converted into an
 * abort and an abort is never converted into a defect.
 */
package de.augmentia.oc.engine.core.error;
