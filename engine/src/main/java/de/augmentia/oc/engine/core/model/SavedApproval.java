package de.augmentia.oc.engine.core.model;

public record SavedApproval(PermissionRequestId id, ProjectId projectId, String action, String resource) {
    public SavedApproval {
        if (id == null || projectId == null) throw new IllegalArgumentException("approval ids must not be null");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action must not be blank");
        if (resource == null || resource.isBlank()) throw new IllegalArgumentException("resource must not be blank");
    }
}