package de.augmentia.oc.engine.core.contract.permission;
import de.augmentia.oc.engine.core.error.*;

public interface PermissionGate {
    /**
     * @throws PermissionDenied model-facing rejection
     * @throws Aborted          deliberate stop, model sees nothing
     */
    void assertAllowed(PermissionRequest request);
}