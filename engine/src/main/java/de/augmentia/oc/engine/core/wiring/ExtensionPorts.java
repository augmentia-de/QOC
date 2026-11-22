package de.augmentia.oc.engine.core.wiring;

import de.augmentia.oc.engine.core.contract.permission.DataPolicy;
import de.augmentia.oc.engine.core.noop.NoOpDataPolicy;
import de.augmentia.oc.engine.core.noop.NoOpDecisionModel;
import de.augmentia.oc.engine.core.noop.NoOpMemoryStore;
import de.augmentia.oc.engine.core.noop.NoOpProvenanceStore;
import de.augmentia.oc.engine.core.port.runtime.DecisionModel;
import de.augmentia.oc.engine.core.port.store.MemoryStore;
import de.augmentia.oc.engine.core.port.store.ProvenanceStore;

/**
 * Optional extension ports (provenance, memory, decision model, data policy).
 * All default to no-op, so the engine works without any extension enabled.
 */
public record ExtensionPorts(
        ProvenanceStore provenance,
        MemoryStore memory,
        DecisionModel decision,
        DataPolicy dataPolicy) {

    public ExtensionPorts {
        if (provenance == null) provenance = NoOpProvenanceStore.INSTANCE;
        if (memory == null) memory = NoOpMemoryStore.INSTANCE;
        if (decision == null) decision = new NoOpDecisionModel();
        if (dataPolicy == null) dataPolicy = NoOpDataPolicy.INSTANCE;
    }

    public static ExtensionPorts defaults() {
        return new ExtensionPorts(null, null, null, null);
    }
}
