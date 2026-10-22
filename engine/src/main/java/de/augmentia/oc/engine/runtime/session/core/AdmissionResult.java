package de.augmentia.oc.engine.runtime.session.core;

import de.augmentia.oc.engine.core.model.InboxId;

/** Outcome of admission; an already known ID is recognized, not admitted twice. */
public sealed interface AdmissionResult {

    record Admitted(InboxId id, boolean created) implements AdmissionResult {}

    record Conflict(String reason) implements AdmissionResult {}

    record SessionNotFound() implements AdmissionResult {}
}
