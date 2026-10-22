package de.augmentia.oc.engine.core.contract.permission;
import de.augmentia.oc.engine.core.json.*;

@FunctionalInterface
public interface PermissionHook {
    Result<PermissionEvaluation> evaluate(PermissionEvaluation evaluation);
}