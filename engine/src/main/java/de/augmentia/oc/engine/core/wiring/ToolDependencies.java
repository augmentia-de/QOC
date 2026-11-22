package de.augmentia.oc.engine.core.wiring;

import de.augmentia.oc.engine.core.contract.tool.ToolRegistrar;
import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import java.nio.file.Path;

public record ToolDependencies(
        FileSystem fileSystem,
        ProcessRunner processRunner,
        HttpTransport httpTransport,
        Path workspacePath,
        ToolRegistrar toolRegistrar
) {
    public ToolDependencies {
        if (workspacePath == null) workspacePath = java.nio.file.Path.of(".").toAbsolutePath().normalize();
        if (toolRegistrar == null) toolRegistrar = sink -> {};
    }

    /** Backward-compatible constructor without toolRegistrar (no-op). */
    public ToolDependencies(FileSystem fileSystem, ProcessRunner processRunner,
                            HttpTransport httpTransport, Path workspacePath) {
        this(fileSystem, processRunner, httpTransport, workspacePath, sink -> {});
    }

    public static ToolDependencies none() {
        return new ToolDependencies(null, null, null, java.nio.file.Path.of(".").toAbsolutePath().normalize(), sink -> {});
    }
}