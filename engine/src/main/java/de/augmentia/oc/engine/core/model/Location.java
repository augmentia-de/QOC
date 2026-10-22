package de.augmentia.oc.engine.core.model;

import java.nio.file.Path;

public record Location(Path directory, ProjectId projectId, Option<String> workspaceId) {
    public Location {
        if (directory == null) throw new IllegalArgumentException("directory must not be null");
        if (projectId == null) throw new IllegalArgumentException("projectId must not be null");
        if (workspaceId == null) workspaceId = Option.none();
    }
}