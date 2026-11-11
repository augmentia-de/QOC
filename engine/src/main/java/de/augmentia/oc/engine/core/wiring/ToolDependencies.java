package de.augmentia.oc.engine.core.wiring;

import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import java.nio.file.Path;

public record ToolDependencies(
        FileSystem fileSystem,
        ProcessRunner processRunner,
        HttpTransport httpTransport,
        Path workspacePath
) {
    public ToolDependencies {
        if (workspacePath == null) workspacePath = java.nio.file.Path.of(".").toAbsolutePath().normalize();
    }

    public static ToolDependencies none() {
        return new ToolDependencies(null, null, null, java.nio.file.Path.of(".").toAbsolutePath().normalize());
    }
}
