package de.augmentia.oc.engine.core.contract.permission;

/** Channel 1: model-facing permission rejection. */
public final class PermissionDenied extends RuntimeException {
    private final transient PermissionRequest request;

    public PermissionDenied(PermissionRequest request, String message) {
        super(message);
        this.request = request;
    }

    public PermissionDenied(PermissionRequest request) {
        this(request, "permission denied");
    }

    public PermissionRequest request() {
        return request;
    }
}